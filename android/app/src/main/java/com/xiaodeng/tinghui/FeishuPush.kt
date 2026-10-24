package com.xiaodeng.tinghui

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * CodeX 在飞书里替你发的东西：
 * 给你自己——纪要好了、开会前上次没办完的；给群——你确认过的会后消息（像你自己发的，不是机器人卡片）
 */
object FeishuPush {
    private fun md(s: String) = JSONObject().put("tag", "markdown").put("content", s)

    /** 底下一行链接：按钮在手机上三个字就被截断，链接不会 */
    private fun buttons(vararg bs: Pair<String, String>) =
        md(bs.filter { it.second.isNotBlank() }.joinToString("　·　") { (t, u) -> "[$t]($u)" })

    private fun card(title: String, sub: String, els: List<JSONObject>) = JSONObject().put("schema", "2.0")
        .put(
            "header", JSONObject().put("title", JSONObject().put("tag", "plain_text").put("content", title))
                .put("subtitle", JSONObject().put("tag", "plain_text").put("content", sub)).put("template", "orange")
        )
        .put("body", JSONObject().put("elements", JSONArray(els)))

    // 「还有几条」前面空一行，不然飞书把它接在上一条后面
    private fun lines(items: List<String>, more: Int) = items.joinToString("\n") { "- $it" } + if (more > 0) "\n\n<font color='grey'>还有 $more 条</font>" else ""

    /** 纪要好了：发给你 */
    fun after(r: Rec) {
        val date = SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(r.startedAt)
        val els = ArrayList<JSONObject>()
        if (r.brief.isNotBlank()) els += md(r.brief)
        val mine = r.todos.filter { it.mine && !it.done }
        if (mine.isNotEmpty()) els += md("**你的 ${mine.size} 条待办**\n" + lines(mine.take(3).map { it.text }, mine.size - 3))
        val open = Todos.points(Store.minutes(r)).filter { it.open }
        if (open.isNotEmpty()) els += md("**还没定 ${open.size}**\n" + lines(open.take(2).map { it.text }, open.size - 2))
        els += buttons("纪要" to r.fs.minutesUrl, "转写稿" to r.fs.transcriptUrl, "会议库" to Prefs.space.value.baseUrl)
        Feishu.sendToMe("interactive", card("纪要好了 · ${r.title}", listOfNotNull(date, clock(r.durationMs), r.folder).joinToString(" · "), els))
    }

    /** 这个日程的上一次：同一个周期日程，或者日程名、会名一样 */
    fun lastSame(e: Cal.Event): Rec? {
        val series = e.uid.substringBeforeLast('@')
        return Store.recs.value.filter { r ->
            r.startedAt < e.start - 10 * 60_000L && r.status == Status.Done && (
                (r.eventUid.contains('@') && r.eventUid.substringBeforeLast('@') == series) ||
                    sameName(r.event.substringBefore('，'), e.title) || sameName(r.title, e.title)
                )
        }.maxByOrNull { it.startedAt }
    }

    /**
     * 日程名算不算同一个会：去掉括号里的会议室、空格和符号再比，一个包含另一个也算。
     * 「FlagOS产品周会-（511会议室）」和「FlagOS产品周会」是同一个；「FlagOS产品双周会」不是
     */
    fun sameName(a: String, b: String): Boolean {
        fun n(s: String) = s.replace(Regex("[（(][^）)]*[）)]"), "").replace(Regex("[\\s·\\-_【】\\[\\]，,。:：]"), "").lowercase()
        val x = n(a)
        val y = n(b)
        if (x.length < 2 || y.length < 2) return false
        return x == y || (minOf(x.length, y.length) >= 4 && (x.contains(y) || y.contains(x)))
    }

    /** 开会前：上次同名会没办完的、没定的，发给你；没有上次就不发 */
    fun before(e: Cal.Event): Boolean {
        val last = lastSame(e) ?: return false
        val undone = last.todos.filter { !it.done }
        val open = Todos.points(Store.minutes(last)).filter { it.open }
        if (undone.isEmpty() && open.isEmpty()) return false
        val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)
        val mins = ((e.start - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val lastDate = SimpleDateFormat("M/d", Locale.CHINA).format(last.startedAt)
        val els = ArrayList<JSONObject>()
        if (undone.isNotEmpty()) els += md("**上次（$lastDate）没办完**\n" + lines(undone.take(5).map { t ->
            t.text + "　<font color='grey'>${if (t.mine) "你" else t.owner.ifBlank { "没说谁" }}</font>"
        }, undone.size - 5))
        if (open.isNotEmpty()) els += md("**还没定**\n" + lines(open.take(3).map { it.text }, open.size - 3))
        els += buttons("上次纪要" to last.fs.minutesUrl, "会议库" to Prefs.space.value.baseUrl)
        val sub = listOf("${fmt.format(e.start)}–${fmt.format(e.end)}", e.location.substringAfterLast('-').substringBefore(" (")).filter { it.isNotBlank() }
            .joinToString(" · ") + if (e.people.isNotEmpty()) " · ${e.people.size} 人" else ""
        Feishu.sendToMe("interactive", card(if (mins > 0) "$mins 分钟后开会 · ${e.title}" else "开会了 · ${e.title}", sub, els))
        return true
    }

    /**
     * 发到群：先存到飞书、给群开纪要的阅读权限，再发。
     * 消息里写到群里有的人名，变成 @ 他；你自己的名字不 @
     */
    /** 发群前的准备：先存到飞书、给这个群开纪要的阅读权限、记下发到过这个群。返回存好的会 */
    fun prepareGroup(r: Rec, chatId: String): Rec {
        var cur = r
        if (cur.fs.minutesDoc.isBlank()) {
            FeishuSync.sync(r.id)
            cur = Store.get(r.id) ?: r
        }
        runCatching { Feishu.grantChat(cur.fs.minutesDoc, "docx", chatId) }
        Prefs.setLastGroup(chatId)
        FeishuSync.markShared(r.id, chatId)
        return Store.get(r.id) ?: cur
    }

    /**
     * 把会上一段原话的录音发到群：前面一行说是哪场会几分几秒，后面是语音，点开就能听。
     * atMs 从这开始，lenMs 多长（最多 60 秒）
     */
    // 一次切一段：同时切几段时手机编码器、上传都在抢
    @Synchronized
    fun sendClip(r: Rec, chatId: String, atMs: Long, lenMs: Long): Long {
        val src = Store.audio(r)
        if (!src.exists()) throw Feishu.FsError("这场会的录音不在手机上")
        val out = java.io.File.createTempFile("clip", ".opus", src.parentFile)
        val len = lenMs.coerceIn(5_000, 60_000)
        val dur = Clip.opus(src, (atMs - 1_000).coerceAtLeast(0), len, out)
        try {
            val key = Feishu.uploadMsgFile(out, "opus", "原话.opus", dur)
            Feishu.send("chat_id", chatId, "text", JSONObject().put("text", "「${r.title}」${clock(atMs)} 的原话"))
            Feishu.send("chat_id", chatId, "audio", JSONObject().put("file_key", key))
        } finally {
            out.delete()
        }
        return dur
    }

    /**
     * 一行字里的人名换成 @：写了「@名字」的一定 @（包括你自己），只写名字的 @ 别人、不 @ 你自己。
     * names 是（叫法, open_id），同一个人一行只 @ 一次
     */
    fun mentions(line: String, names: List<Pair<String, String>>, me: String): JSONArray {
        val sorted = names.sortedByDescending { it.first.length }
        val row = JSONArray()
        val sb = StringBuilder()
        val done = HashSet<String>()
        fun flush() {
            if (sb.isNotEmpty()) row.put(JSONObject().put("tag", "text").put("text", sb.toString()))
            sb.clear()
        }
        var i = 0
        while (i < line.length) {
            val at = line[i] == '@' || line[i] == '＠'
            val start = if (at) i + 1 else i
            val hit = sorted.firstOrNull { (n, id) -> id !in done && line.startsWith(n, start) && (at || id != me) }
            if (hit != null) {
                flush()
                row.put(JSONObject().put("tag", "at").put("user_id", hit.second))
                done += hit.second
                i = start + hit.first.length
            } else {
                sb.append(line[i])
                i++
            }
        }
        flush()
        return row
    }

    /** 飞书里打开这个群 */
    fun chatLink(chatId: String) = "https://applink.feishu.cn/client/chat/open?openChatId=$chatId"

    fun toGroup(r: Rec, chatId: String, text: String) {
        val cur = prepareGroup(r, chatId)
        textToGroup(chatId, text, "纪要" to cur.fs.minutesUrl)
    }

    /**
     * 发一段话到群：写到群里有的人名，变成 @ 他（你自己的名字不 @）；link 给了就在最后附一个链接
     */
    fun textToGroup(chatId: String, text: String, link: Pair<String, String>? = null) {
        val me = Feishu.me()
        val members = runCatching { Feishu.members(chatId) }.getOrDefault(emptyList())
        members.firstOrNull { it.openId == me }?.let { Prefs.setFeishuName(it.name) }
        // 群里每个人可能被叫的名字：全名、点前面、点后面；你自己再加上平时的叫法（「小明」）
        val names = members.flatMap { m ->
            val extra = if (m.openId == me) listOfNotNull(BuildConfig.FEISHU_USER_NAME, Voice.me()?.name) else emptyList()
            (listOf(m.name, m.name.substringBefore('·'), m.name.substringAfterLast('·')) + extra)
                .map { it.trim() }.filter { it.length >= 2 }.distinct().map { it to m.openId }
        }
        val content = JSONArray()
        text.lines().filter { it.isNotBlank() }.forEach { content.put(mentions(it, names, me)) }
        if (link != null && link.second.isNotBlank()) content.put(JSONArray().put(JSONObject().put("tag", "a").put("text", link.first).put("href", link.second)))
        Feishu.send("chat_id", chatId, "post", JSONObject().put("zh_cn", JSONObject().put("title", "").put("content", content)))
    }
}
