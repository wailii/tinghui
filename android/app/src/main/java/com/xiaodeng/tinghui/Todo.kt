package com.xiaodeng.tinghui

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 一条待办：从纪要的待办表里拆出来。
 * at：会上第几毫秒说的，点了能回去听原话；due：截止时间，会上没说就是 0
 */
data class Todo(
    val key: String,
    val text: String,
    val owner: String,
    val mine: Boolean,
    val dueText: String,
    val due: Long,
    val at: Long,
    val done: Boolean = false,
    val task: String = "",
    val taskUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("text", text).put("owner", owner).put("mine", mine)
        .put("dueText", dueText).put("due", due).put("at", at).put("done", done).put("task", task).put("taskUrl", taskUrl)

    companion object {
        fun fromJson(o: JSONObject) = Todo(
            o.optString("key"), o.optString("text"), o.optString("owner"), o.optBoolean("mine"),
            o.optString("dueText"), o.optLong("due"), o.optLong("at"), o.optBoolean("done"),
            o.optString("task"), o.optString("taskUrl"),
        )
    }
}

/** 一条结论或没定下来的事 */
data class Point(val text: String, val open: Boolean, val at: Long)

object Todos {
    private val TIME = Regex("""[（(]?\[(\d{1,2}:\d{2}(?::\d{2})?)](?:\s*\[\d{1,2}:\d{2}(?::\d{2})?])*[）)]?""")

    private fun clock(s: String): Long {
        val p = s.split(":").map { it.toLongOrNull() ?: 0 }
        return (if (p.size == 3) p[0] * 3600 + p[1] * 60 + p[2] else p[0] * 60 + p.getOrElse(1) { 0 }) * 1000
    }

    /** 去掉时间戳，返回干净的字和第一个时间 */
    fun strip(s: String): Pair<String, Long> {
        val m = TIME.find(s)
        val at = m?.groupValues?.get(1)?.let { clock(it) } ?: -1L
        return s.replace(TIME, "").replace(Regex("\\s+"), " ").trim().trimEnd('；', ';', '，', ',') to at
    }

    /** 你的名字：声纹认出的你、打包时带的名字；「我」也算 */
    fun myNames(): List<String> = listOfNotNull(Voice.me()?.name, BuildConfig.FEISHU_USER_NAME.ifBlank { null }, Prefs.feishuName.value.ifBlank { null })
        .flatMap { n -> listOf(n, n.substringAfterLast('·')) }.filter { it.length >= 2 }.distinct()

    fun isMine(owner: String): Boolean {
        val o = owner.replace(" ", "")
        return o == "我" || myNames().any { o.contains(it.replace(" ", "")) }
    }

    private fun norm(s: String) = s.replace(Regex("[\\s，,。.；;、：:「」\"“”]"), "")

    /** 纪要里的待办表 → 待办；以前拆过的（同一件事）保留做没做完、建没建任务 */
    /** 一条待办在纪要里的原样：谁、什么事（带时间戳）、截止 */
    private class Raw(val owner: String, val what: String, val due: String)

    /** 「负责人 | 事项 | 截止」这种表 */
    private fun fromTable(blocks: List<Md.Block>): List<Raw>? {
        val table = blocks.filterIsInstance<Md.Table>().firstOrNull { t ->
            t.head.any { it.contains("负责") || it.contains("谁") } && t.head.any { it.contains("事") || it.contains("任务") }
        } ?: return null
        fun col(vararg ks: String) = t(table).indexOfFirst { h -> ks.any { h.contains(it) } }
        val cOwner = col("负责", "谁")
        val cWhat = col("事项", "事情", "任务", "内容", "事")
        val cDue = col("截止", "时间", "日期", "什么时候")
        return table.rows.map { row ->
            Raw(Md.plain(row.getOrElse(cOwner) { "" }), Md.plain(row.getOrElse(cWhat) { "" }), if (cDue >= 0) Md.plain(row.getOrElse(cDue) { "" }) else "")
        }
    }

    private val DUE_IN_TEXT = Regex("""[（(]\s*(?:截止|DDL|ddl)[：:\s]*([^）)]+)[）)]""")

    private val OWNER_LINE = Regex("""^([^：:\[]{1,24})[：:]\s*(.+)$""")

    private fun line(text: String, anyLine: Boolean): Raw? {
        val m = OWNER_LINE.find(text)
        if (m == null && !anyLine) return null
        val what = m?.groupValues?.get(2) ?: text
        val due = DUE_IN_TEXT.find(what)?.groupValues?.get(1).orEmpty()
        return Raw(m?.groupValues?.get(1).orEmpty(), what.replace(DUE_IN_TEXT, ""), due)
    }

    /** 「待办」一节下面一行一条：「小明：补数据导入功能 [05:48]」 */
    private fun fromList(blocks: List<Md.Block>): List<Raw> {
        val out = ArrayList<Raw>()
        var level = 0
        for (b in blocks) {
            when (b) {
                is Md.Head -> level = when {
                    Regex("待办|行动项|谁做|接下来|后续跟进|下一步").containsMatchIn(b.text) -> b.level
                    level > 0 && b.level <= level -> 0
                    else -> level
                }
                is Md.Item -> if (level > 0 && b.depth == 0) line(Md.plain(b.text).trim(), true)?.let { out += it }
                // 没加「-」的：只认「谁：什么事」这种，免得把整段话当成待办
                is Md.Para -> if (level > 0) b.text.lines().mapNotNull { line(Md.plain(it).trim(), false) }.forEach { out += it }
                else -> {}
            }
        }
        return out
    }

    fun parse(minutes: String, startedAt: Long, old: List<Todo>): List<Todo> {
        val blocks = Md.parse(minutes)
        val raws = fromTable(blocks) ?: fromList(blocks)
        return raws.mapIndexedNotNull { i, raw ->
            val (text, at) = strip(raw.what)
            if (text.isBlank()) return@mapIndexedNotNull null
            val owner = raw.owner.trim()
            val dueText = strip(raw.due).first.trim().trim('—', '-', ' ')
            val prev = old.firstOrNull { norm(it.text) == norm(text) }
                ?: old.firstOrNull { o -> norm(o.text).let { it.length > 6 && (norm(text).contains(it) || it.contains(norm(text))) } }
            Todo(
                key = prev?.key ?: "t${startedAt}_${i}_${norm(text).hashCode().toUInt().toString(36)}",
                text = text, owner = owner, mine = isMine(owner),
                dueText = dueText, due = dueOf(dueText, startedAt), at = at,
                done = prev?.done ?: false, task = prev?.task ?: "", taskUrl = prev?.taskUrl ?: "",
            )
        }
    }

    private fun t(table: Md.Table) = table.head.map { Md.plain(it) }

    /** 纪要里的「结论」和「没定下来的」两节 */
    fun points(minutes: String): List<Point> {
        val out = ArrayList<Point>()
        var mode = 0 // 1 结论，2 没定
        for (b in Md.parse(minutes)) {
            when (b) {
                is Md.Head -> mode = when {
                    b.text.contains("没定") || b.text.contains("待定") || b.text.contains("悬") || b.text.contains("风险") -> 2
                    b.level <= 2 && (b.text.contains("结论") || b.text.contains("定了") || b.text.contains("决定")) -> 1
                    b.level <= 2 -> 0
                    else -> mode
                }
                is Md.Item -> if (mode != 0 && b.depth == 0) {
                    val (text, at) = strip(Md.plain(b.text))
                    if (text.isNotBlank()) out += Point(text, mode == 2, at)
                }
                else -> {}
            }
        }
        return out
    }

    private val WEEK = mapOf('一' to DayOfWeek.MONDAY, '二' to DayOfWeek.TUESDAY, '三' to DayOfWeek.WEDNESDAY, '四' to DayOfWeek.THURSDAY,
        '五' to DayOfWeek.FRIDAY, '六' to DayOfWeek.SATURDAY, '日' to DayOfWeek.SUNDAY, '天' to DayOfWeek.SUNDAY)

    /** 会上说的「下周一」「明天」「10月26日」换成具体时间；说不清的返回 0 */
    fun dueOf(text: String, meetingAt: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val s = text.replace(" ", "")
        if (s.isBlank() || s == "—") return 0
        val day0 = Instant.ofEpochMilli(meetingAt).atZone(zone).toLocalDate()
        fun at(d: LocalDate, h: Int = 18) = d.atTime(LocalTime.of(h, 0)).atZone(zone).toInstant().toEpochMilli()
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(s)?.let { m ->
            return runCatching { at(LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())) }.getOrDefault(0)
        }
        Regex("""(\d{1,2})[月/.](\d{1,2})""").find(s)?.let { m ->
            val mo = m.groupValues[1].toInt()
            val d = m.groupValues[2].toInt()
            if (mo in 1..12 && d in 1..31) {
                var date = runCatching { LocalDate.of(day0.year, mo, d) }.getOrNull() ?: return 0
                if (date.isBefore(day0.minusDays(180))) date = date.plusYears(1)
                return at(date)
            }
        }
        Regex("""(下下|下个?|这个?|本)?(周|星期|礼拜)([一二三四五六日天])""").find(s)?.let { m ->
            val dow = WEEK[m.groupValues[3][0]] ?: return 0
            val thisWeek = day0.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val base = when {
                m.groupValues[1].startsWith("下下") -> thisWeek.plusWeeks(2)
                m.groupValues[1].startsWith("下") -> thisWeek.plusWeeks(1)
                else -> thisWeek
            }
            var date = base.with(TemporalAdjusters.nextOrSame(dow))
            if (m.groupValues[1].isEmpty() && date.isBefore(day0)) date = date.plusWeeks(1)
            return at(date)
        }
        return when {
            s.contains("后天") -> at(day0.plusDays(2))
            s.contains("明天") || s.contains("明早") -> at(day0.plusDays(1))
            s.contains("晚上") || s.contains("今晚") -> at(day0, 21)
            s.contains("今天") || s.contains("待会") || s.contains("一会") || s.contains("会后") || s.contains("马上") || s.contains("当天") -> at(day0)
            s.contains("下周") -> at(day0.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)))
            s.contains("本周") || s.contains("这周") -> at(day0.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)))
            s.contains("月底") -> at(day0.with(TemporalAdjusters.lastDayOfMonth()))
            else -> 0
        }
    }

    fun toJson(l: List<Todo>) = JSONArray(l.map { it.toJson() })
    fun fromJson(a: JSONArray?) = a.objs().map { Todo.fromJson(it) }
}
