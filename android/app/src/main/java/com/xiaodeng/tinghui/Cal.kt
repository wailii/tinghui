package com.xiaodeng.tinghui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 飞书日历：用飞书自带的 CalDAV 同步读日程（caldav.feishu.cn，飞书里生成的用户名和密码），
 * 不走开放平台，CodeX 不用加权限。
 * 读接下来一天半的会，开会前几分钟发提醒，点一下开录；标题和参会人带进这场录音。
 */
object Cal {
    private const val HOST = "https://caldav.feishu.cn"

    data class Event(
        val uid: String,
        val title: String,
        val start: Long,
        val end: Long,
        val people: List<String>,
        val location: String,
    ) {
        /** 给大模型看的一句话：标题、时间、地点、参会人 */
        fun context(): String = buildString {
            append(title)
            if (location.isNotBlank()) append("，地点：$location")
            if (people.isNotEmpty()) append("，参会人：${people.joinToString("、")}")
        }
    }

    class CalError(msg: String) : IOException(msg)

    private val _fresh = MutableStateFlow<Set<String>>(emptySet())
    /** 最近 12 小时里新出现的日程（首页标「新」） */
    val fresh: StateFlow<Set<String>> = _fresh
    private lateinit var seenFile: File

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    /** 接下来的会（缓存，App 打开、闹钟、开机时刷新） */
    val events: StateFlow<List<Event>> = _events
    private lateinit var cache: File

    fun init(ctx: Context) {
        cache = File(ctx.filesDir, "calendar.json")
        seenFile = File(ctx.filesDir, "calendar_seen.json")
        _events.value = runCatching { fromJson(JSONArray(cache.readText())) }.getOrDefault(emptyList())
        markFresh(_events.value)
    }

    // ---------- CalDAV ----------

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private val XML = "application/xml; charset=utf-8".toMediaType()

    private fun dav(method: String, url: String, depth: Int, body: String, cfg: Prefs.Cal): String {
        val req = okhttp3.Request.Builder()
            .url(url)
            .method(method, body.toRequestBody(XML))
            .header("Authorization", okhttp3.Credentials.basic(cfg.user, cfg.pass))
            .header("Depth", depth.toString())
            .build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (r.code == 401) throw CalError("用户名或密码不对：到飞书日历设置里重新生成一组 CalDAV 密码")
                if (r.code == 403) throw CalError("飞书不让读这个日历（403）：可能是公司关了 CalDAV 同步")
                if (!r.isSuccessful) throw CalError("飞书日历返回 ${r.code}")
                return text
            }
        } catch (e: CalError) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw CalError("连不上飞书日历：检查网络")
        } catch (e: IOException) {
            throw CalError("连不上飞书日历：${e.message ?: "网络错误"}")
        }
    }

    private fun abs(href: String) = if (href.startsWith("http")) href else HOST + href

    private fun tag(name: String) = Regex("""<(?:[\w-]+:)?$name\b[^>]*>(.*?)</(?:[\w-]+:)?$name>""", RegexOption.DOT_MATCHES_ALL)
    private val HREF = tag("href")

    private fun hrefIn(xml: String, prop: String): String? =
        tag(prop).find(xml)?.groupValues?.get(1)?.let { HREF.find(it)?.groupValues?.get(1)?.trim() }

    /** 找到你的日历：principal → calendar-home-set → 里面每个装日程的日历 */
    private fun calendars(cfg: Prefs.Cal): List<String> {
        val p1 = dav(
            "PROPFIND", "$HOST/", 0,
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>""",
            cfg,
        )
        val principal = hrefIn(p1, "current-user-principal") ?: throw CalError("飞书日历没返回你的账号信息")
        val p2 = dav(
            "PROPFIND", abs(principal), 0,
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>""",
            cfg,
        )
        val home = hrefIn(p2, "calendar-home-set") ?: principal
        val p3 = dav(
            "PROPFIND", abs(home), 1,
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:resourcetype/><d:displayname/><c:supported-calendar-component-set/></d:prop></d:propfind>""",
            cfg,
        )
        return tag("response").findAll(p3).mapNotNull { m ->
            val r = m.groupValues[1]
            val type = tag("resourcetype").find(r)?.groupValues?.get(1) ?: ""
            if (!Regex("""<(?:[\w-]+:)?calendar\s*/?>""").containsMatchIn(type)) return@mapNotNull null
            val comps = tag("supported-calendar-component-set").find(r)?.groupValues?.get(1)
            if (comps != null && comps.contains("name=") && !comps.contains("VEVENT")) return@mapNotNull null
            HREF.find(r)?.groupValues?.get(1)?.trim()
        }.toList().ifEmpty { throw CalError("飞书日历里没找到日历") }
    }

    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /**
     * 读 [from, to] 之间的日程。飞书的查询只回日程地址不回内容，再按地址成批取；
     * 重复日程飞书不展开，自己按规则算
     */
    fun fetch(cfg: Prefs.Cal, from: Long, to: Long): List<Event> {
        val s = UTC.format(Instant.ofEpochMilli(from))
        val e = UTC.format(Instant.ofEpochMilli(to))
        val out = ArrayList<Event>()
        for (cal in calendars(cfg)) {
            val xml = dav(
                "REPORT", abs(cal), 1,
                """<?xml version="1.0"?><c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
<d:prop><d:getetag/><c:calendar-data/></d:prop>
<c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"><c:time-range start="$s" end="$e"/></c:comp-filter></c:comp-filter></c:filter>
</c:calendar-query>""",
                cfg,
            )
            val datas = ArrayList<String>()
            val missing = ArrayList<String>()
            tag("response").findAll(xml).forEach { m ->
                val r = m.groupValues[1]
                val data = tag("calendar-data").find(r)?.groupValues?.get(1)?.takeIf { it.contains("VEVENT") }
                if (data != null) datas += data else HREF.find(r)?.groupValues?.get(1)?.trim()?.let { missing += it }
            }
            missing.chunked(40).forEach { batch ->
                val got = dav(
                    "REPORT", abs(cal), 1,
                    """<?xml version="1.0"?><c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-data/></d:prop>""" +
                        batch.joinToString("") { "<d:href>$it</d:href>" } + "</c:calendar-multiget>",
                    cfg,
                )
                tag("calendar-data").findAll(got).forEach { datas += it.groupValues[1] }
            }
            datas.forEach { out += parseIcs(unxml(it), from, to) }
        }
        // 同一个会出现在好几个日历里只留一个
        return out.distinctBy { it.uid }.sortedBy { it.start }
    }

    /** XML 转义还原：飞书的换行写成 &#xD;&#xA; */
    private fun unxml(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")
        .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    // ---------- iCalendar ----------

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    private fun props(block: String): List<Prop> = block.lines().mapNotNull { l ->
        val colon = indexOfValue(l)
        if (colon < 0) return@mapNotNull null
        val head = l.substring(0, colon)
        val parts = head.split(';')
        val params = parts.drop(1).associate { p ->
            val k = p.substringBefore('=').uppercase()
            k to p.substringAfter('=', "").trim('"')
        }
        Prop(parts[0].uppercase(), params, l.substring(colon + 1))
    }

    /** 参数里的引号中可能有冒号，找第一个不在引号里的冒号 */
    private fun indexOfValue(l: String): Int {
        var q = false
        for (i in l.indices) {
            when (l[i]) {
                '"' -> q = !q
                ':' -> if (!q) return i
            }
        }
        return -1
    }

    private fun text(v: String) = v.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()

    /** 时间：带 Z 是 UTC，带 TZID 按那个时区，都没有按手机时区；只有日期的是全天日程，返回 null */
    private fun time(p: Prop?): Long? {
        p ?: return null
        val v = p.value.trim()
        if (p.params["VALUE"] == "DATE" || v.length == 8) return null
        val local = runCatching { LocalDateTime.parse(v.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")) }.getOrNull() ?: return null
        val zone = when {
            v.endsWith("Z") -> ZoneOffset.UTC
            p.params["TZID"] != null -> runCatching { ZoneId.of(p.params["TZID"]) }.getOrDefault(ZoneId.systemDefault())
            else -> ZoneId.systemDefault()
        }
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    private fun duration(v: String): Long {
        val m = Regex("""P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").find(v) ?: return 3600_000
        val g = m.groupValues.drop(1).map { it.toLongOrNull() ?: 0 }
        return (((g[0] * 7 + g[1]) * 24 + g[2]) * 60 + g[3]) * 60_000 + g[4] * 1000
    }

    fun parseIcs(ics: String, from: Long, to: Long): List<Event> {
        val unfolded = ics.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "")
        val blocks = Regex("BEGIN:VEVENT\n(.*?)END:VEVENT", RegexOption.DOT_MATCHES_ALL).findAll(unfolded).map { props(it.groupValues[1]) }.toList()
        // 重复日程里单独改过的那一次（RECURRENCE-ID）：顶掉规则算出来的那一次
        val overridden = blocks.mapNotNull { b ->
            val rid = time(b.firstOrNull { it.name == "RECURRENCE-ID" }) ?: return@mapNotNull null
            (b.firstOrNull { it.name == "UID" }?.value ?: "") to rid
        }.toSet()
        val out = ArrayList<Event>()
        for (b in blocks) {
            fun one(n: String) = b.firstOrNull { it.name == n }
            if (one("STATUS")?.value?.trim()?.uppercase() == "CANCELLED") continue
            val start = time(one("DTSTART")) ?: continue
            val end = time(one("DTEND")) ?: (start + (one("DURATION")?.value?.let { duration(it) } ?: 3600_000))
            val uid = one("UID")?.value?.trim() ?: "$start"
            // 会议室、群（飞书把群写成 noreply_…@calendar.feishu.cn 的参会人）不算人
            val people = (b.filter { it.name == "ORGANIZER" } + b.filter { it.name == "ATTENDEE" })
                .filter { it.params["CUTYPE"]?.uppercase().let { t -> t == null || t == "INDIVIDUAL" } }
                .filterNot { it.value.contains("noreply", true) || it.value.contains("calendar.feishu.cn", true) }
                .mapNotNull { it.params["CN"]?.let { n -> text(n) }?.ifBlank { null } }
                .distinct()
            val title = text(one("SUMMARY")?.value ?: "").ifBlank { "（没有标题的会）" }
            val loc = text(one("LOCATION")?.value ?: "")
            val rrule = one("RRULE")?.value
            val starts = if (rrule == null || one("RECURRENCE-ID") != null) listOf(start)
            else expand(rrule, start, b.filter { it.name == "EXDATE" }.flatMap { p -> p.value.split(',').mapNotNull { v -> time(Prop("EXDATE", p.params, v)) } }.toSet(), from, to)
                .filter { (uid to it) !in overridden }
            val len = end - start
            for (st in starts) if (st + len > from && st < to) out += Event("$uid@$st", title, st, st + len, people, loc)
        }
        return out
    }

    /** 服务器没展开重复日程时自己算：只管常见的每天、每周（可带 BYDAY）、每月同一天 */
    private fun expand(rule: String, start: Long, ex: Set<Long>, from: Long, to: Long): List<Long> {
        val r = rule.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=', "") }
        val freq = r["FREQ"] ?: return listOf(start)
        val interval = r["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = r["COUNT"]?.toIntOrNull()
        val until = r["UNTIL"]?.let { u ->
            time(Prop("UNTIL", emptyMap(), if (u.length == 8) u + "T235959" else u))
        }
        val zone = ZoneId.systemDefault()
        val first = Instant.ofEpochMilli(start).atZone(zone)
        val days = r["BYDAY"]?.split(',')?.mapNotNull { d ->
            when (d.takeLast(2)) {
                "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY
                "TH" -> DayOfWeek.THURSDAY; "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY
                "SU" -> DayOfWeek.SUNDAY; else -> null
            }
        }?.toSet()
        val out = ArrayList<Long>()
        var n = 0
        var day: LocalDate = first.toLocalDate()
        val firstDay = day
        // 最多往后数 3 年，够用
        while (day.isBefore(firstDay.plusYears(3))) {
            val ok = when (freq) {
                "DAILY" -> java.time.temporal.ChronoUnit.DAYS.between(firstDay, day) % interval == 0L
                "WEEKLY" -> {
                    val weeks = java.time.temporal.ChronoUnit.WEEKS.between(firstDay.with(DayOfWeek.MONDAY), day.with(DayOfWeek.MONDAY))
                    weeks % interval == 0L && day.dayOfWeek in (days ?: setOf(firstDay.dayOfWeek))
                }
                "MONTHLY" -> day.dayOfMonth == firstDay.dayOfMonth &&
                    java.time.temporal.ChronoUnit.MONTHS.between(firstDay.withDayOfMonth(1), day.withDayOfMonth(1)) % interval == 0L
                else -> day == firstDay
            }
            if (ok) {
                val t = day.atTime(first.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                if (until != null && t > until) break
                n++
                if (count != null && n > count) break
                if (t !in ex && t >= from - 86_400_000) out += t
                if (t > to) break
            }
            day = day.plusDays(1)
        }
        return out
    }

    // ---------- 缓存、提醒 ----------

    private fun toJson(l: List<Event>) = JSONArray(l.map {
        JSONObject().put("uid", it.uid).put("title", it.title).put("start", it.start).put("end", it.end)
            .put("people", JSONArray(it.people)).put("loc", it.location)
    })

    private fun fromJson(a: JSONArray) = a.objs().map {
        Event(
            it.getString("uid"), it.optString("title"), it.optLong("start"), it.optLong("end"),
            it.optJSONArray("people")?.let { p -> (0 until p.length()).map { i -> p.getString(i) } } ?: emptyList(),
            it.optString("loc"),
        )
    }

    fun get(uid: String) = testEvent?.takeIf { it.uid == uid } ?: _events.value.firstOrNull { it.uid == uid }

    @Volatile private var testEvent: Event? = null

    /**
     * 试一下会前提醒：拿最近一场对上日程的会，假装同一个日程 15 分钟后又开，
     * 10 秒后按真提醒的路子走一遍（系统闹钟 → 通知 → CodeX 发上次没办完的）。返回用的是哪个日程
     */
    fun test(ctx: Context): String? {
        val r = Store.recs.value.filter { it.eventUid.contains('@') && it.status == Status.Done }.maxByOrNull { it.startedAt } ?: return null
        val now = System.currentTimeMillis()
        val title = r.event.substringBefore('，').ifBlank { r.title }
        val e = Event("${r.eventUid.substringBeforeLast('@')}@test$now", title, now + 15 * 60_000L, now + 75 * 60_000L, emptyList(), "")
        testEvent = e
        val am = ctx.getSystemService(AlarmManager::class.java)
        listOf(ACT_REMIND to 90_001, ACT_BRIEF to 90_002).forEach { (act, code) ->
            val p = pi(ctx, act, code, e.uid)
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 10_000L, p)
            else am.set(AlarmManager.RTC_WAKEUP, now + 10_000L, p)
        }
        return title
    }

    /** 接上了就试一次：返回接下来一天里有几场会 */
    fun test(cfg: Prefs.Cal): List<Event> {
        val now = System.currentTimeMillis()
        return fetch(cfg, now - 3600_000, now + 24 * 3600_000L)
    }

    private val syncing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 刷新接下来 36 小时的会，排好提醒；没接日历就清掉 */
    fun sync(ctx: Context) {
        val cfg = Prefs.cal.value
        if (!cfg.ready) {
            _events.value = emptyList()
            runCatching { cache.delete() }
            schedule(ctx)
            return
        }
        if (!syncing.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            val l = fetch(cfg, now - 2 * 3600_000L, now + 36 * 3600_000L)
            _events.value = l
            runCatching { cache.writeText(toJson(l).toString()) }
            markFresh(l)
            linkRecs(cfg, now)
        } catch (e: Throwable) {
            // 网不好就先用上次的
        } finally {
            syncing.set(false)
            schedule(ctx)
            Shortcuts.update(ctx)
        }
    }

    /**
     * 没从日历开录的会：按开始时间对上当时的日程，记下是哪一场。
     * 会前提醒靠它找「上一次」——录音的会名是模型起的，跟日程名对不上。对不上的记成 "-"，不再查
     */
    private fun linkRecs(cfg: Prefs.Cal, now: Long) {
        val todo = Store.recs.value.filter { it.eventUid.isBlank() && it.startedAt in (now - 14 * 86_400_000L)..now }
        if (todo.isEmpty()) return
        val evs = runCatching { fetch(cfg, todo.minOf { it.startedAt } - 3 * 3600_000L, now) }.getOrNull() ?: return
        for (r in todo) {
            val e = evs.filter { it.end - it.start < 20 * 3600_000L && r.startedAt in (it.start - 15 * 60_000L)..(it.end - 5 * 60_000L) }
                .minByOrNull { kotlin.math.abs(it.start - r.startedAt) }
            Store.update(r.id) { it.copy(eventUid = e?.uid ?: "-", event = it.event.ifBlank { e?.context().orEmpty() }) }
        }
    }

    /** 每个日程第一次看到的时间；第一次接日历时看到的都不算新 */
    @Synchronized
    private fun markFresh(l: List<Event>) {
        val now = System.currentTimeMillis()
        val seen = runCatching { JSONObject(seenFile.readText()) }.getOrNull()
        val first = seen == null
        val o = seen ?: JSONObject()
        l.forEach { if (!o.has(it.uid)) o.put(it.uid, if (first) 0L else now) }
        // 过期太久的不留
        o.keys().asSequence().toList().forEach { k -> if (l.none { it.uid == k } && now - o.optLong(k) > 7 * 86_400_000L) o.remove(k) }
        runCatching { seenFile.writeText(o.toString()) }
        _fresh.value = l.filter { now - o.optLong(it.uid) < 12 * 3600_000L }.map { it.uid }.toSet()
    }

    private const val ACT_REMIND = "com.xiaodeng.tinghui.MEET_REMIND"
    /** 开会前 15 分钟：CodeX 把上次没办完的发给你 */
    private const val ACT_BRIEF = "com.xiaodeng.tinghui.CAL_BRIEF"
    private const val ACT_SYNC = "com.xiaodeng.tinghui.CAL_SYNC"

    private fun pi(ctx: Context, action: String, code: Int, uid: String? = null): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, CalReceiver::class.java).setAction(action).apply { if (uid != null) putExtra("uid", uid) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 每场会开始前 lead 分钟一个闹钟；另外每 3 小时自己刷新一次日程 */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val cfg = Prefs.cal.value
        val sp = ctx.getSharedPreferences("tinghui", Context.MODE_PRIVATE)
        // 先撤掉上次排的
        sp.getStringSet("calAlarms", emptySet())!!.forEach { k ->
            val act = if (k.startsWith("B")) ACT_BRIEF else ACT_REMIND
            k.trimStart('B', 'R').toIntOrNull()?.let { am.cancel(pi(ctx, act, it)) }
        }
        am.cancel(pi(ctx, ACT_SYNC, 7))
        if (!cfg.ready) {
            sp.edit().remove("calAlarms").apply()
            return
        }
        val now = System.currentTimeMillis()
        val codes = HashSet<String>()
        fun set(act: String, code: Int, at: Long, uid: String) {
            val p = pi(ctx, act, code, uid)
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setWindow(AlarmManager.RTC_WAKEUP, at - 60_000, 60_000, p)
        }
        for (e in _events.value) {
            val code = e.uid.hashCode()
            val at = e.start - cfg.lead * 60_000L
            if (cfg.remind && at > now) {
                set(ACT_REMIND, code, at, e.uid)
                codes += "R$code"
            }
            // 上次没办完的提前 15 分钟发，留时间看
            val brief = e.start - 15 * 60_000L
            if (Prefs.push.value.before && brief > now) {
                set(ACT_BRIEF, code + 1, brief, e.uid)
                codes += "B${code + 1}"
            }
        }
        sp.edit().putStringSet("calAlarms", codes).apply()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 3 * 3600_000L, pi(ctx, ACT_SYNC, 7))
    }

    fun onAlarm(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACT_REMIND -> {
                val e = intent.getStringExtra("uid")?.let { get(it) } ?: return
                // 已经在录了就不打扰
                if (!Live.state.value.recording) Notifs.meeting(ctx, e)
            }
            ACT_BRIEF -> {
                val e = intent.getStringExtra("uid")?.let { get(it) } ?: return
                if (Prefs.push.value.before && Prefs.feishu.value.ready) runCatching { FeishuPush.before(e) }
            }
            else -> sync(ctx)
        }
    }
}

/** 开会提醒、定时刷新日程、开机后重排提醒 */
class CalReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val r = goAsync()
        kotlin.concurrent.thread {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Cal.sync(ctx)
                else Cal.onAlarm(ctx, intent)
            } catch (e: Throwable) {
            } finally {
                r.finish()
            }
        }
    }
}
