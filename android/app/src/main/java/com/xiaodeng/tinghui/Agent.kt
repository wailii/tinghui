package com.xiaodeng.tinghui

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 小灯能做的事：发一段话到飞书群、把纪要或转写稿的链接发到群、给你建飞书任务。
 * 模型只提出要做什么，每件事都先变成一张卡片，你点「确认」才真去做
 */
object Agent {
    /** 一件要做的事。state：wait 等你确认，run 在做，done 做完，fail 没做成，skip 你取消了 */
    data class Act(val id: String, val kind: String, val args: JSONObject, val state: String = "wait", val result: String = "") {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("args", args).put("state", state).put("result", result)

        companion object {
            fun fromJson(o: JSONObject) = Act(o.optString("id"), o.optString("kind"), o.optJSONObject("args") ?: JSONObject(), o.optString("state", "wait"), o.optString("result"))
        }
    }

    private fun fn(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject().put("type", "function").put(
        "function", JSONObject().put("name", name).put("description", desc)
            .put(
                "parameters", JSONObject().put("type", "object").put(
                    "properties", props.put(
                        "direct",
                        JSONObject().put("type", "boolean").put("description", "他在最新这句话里明确说了不用确认、直接发、直接做时填 true，其他情况不填"),
                    )
                ).put("required", JSONArray(required))
            )
    )

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    fun tools(): JSONArray = JSONArray()
        .put(
            fn(
                "send_to_group", "把一段文字发到飞书群（CodeX 在的群）。会先给用户确认再发",
                JSONObject().put("group", str("群名，从可用的群里选"))
                    .put("text", str("要发的话：书面、简洁，不加粗，不用表情。要 @ 谁就写「@名字」，名字照群成员名单里的写"))
                    .put("meeting", str("相关会议的编号，填了会在后面附上这场会的纪要链接，可不填")),
                listOf("group", "text"),
            )
        )
        .put(
            fn(
                "send_link", "把某场会的纪要或转写稿链接发到飞书群，并给群开查看权限",
                JSONObject().put("group", str("群名"))
                    .put("meeting", str("会议编号"))
                    .put("doc", JSONObject().put("type", "string").put("enum", JSONArray(listOf("minutes", "transcript"))).put("description", "minutes 纪要，transcript 转写稿")),
                listOf("group", "meeting", "doc"),
            )
        )
        .put(
            fn(
                "send_clip", "把会上某段原话的录音（语音消息）发到飞书群。时间点从转写稿那一行开头的 [mm:ss] 取",
                JSONObject().put("group", str("群名"))
                    .put("meeting", str("会议编号"))
                    .put("start", str("从几分几秒开始，格式 mm:ss 或 h:mm:ss"))
                    .put("seconds", JSONObject().put("type", "integer").put("description", "发多长，秒，默认 30，最多 60")),
                listOf("group", "meeting", "start"),
            )
        )
        .put(
            fn(
                "create_task", "给用户自己建一条飞书任务（负责人只能是他自己）",
                JSONObject().put("summary", str("任务标题，一句话"))
                    .put("due", str("截止时间，格式 2026-10-24 18:00 或 2026-10-24，没说就不填"))
                    .put("meeting", str("相关会议编号，可不填")),
                listOf("summary"),
            )
        )

    @Volatile private var groupCache: Pair<Long, List<Feishu.Chat>>? = null

    /** CodeX 在的群，10 分钟内不重复拉 */
    fun groups(): List<Feishu.Chat> {
        groupCache?.let { (at, l) -> if (System.currentTimeMillis() - at < 600_000) return l }
        val l = Feishu.chats()
        groupCache = System.currentTimeMillis() to l
        return l
    }

    @Volatile private var memberCache: Map<String, Pair<Long, List<String>>> = emptyMap()

    /** 群里有谁（名字），10 分钟内不重复拉；拉不到就空 */
    fun memberNames(chatId: String): List<String> {
        memberCache[chatId]?.let { (at, l) -> if (System.currentTimeMillis() - at < 600_000) return l }
        val me = runCatching { Feishu.me() }.getOrDefault("")
        // 你自己写成平时的叫法，后面带飞书全名，模型才知道「小明」就是你
        val call = Voice.me()?.name ?: BuildConfig.FEISHU_USER_NAME
        val l = runCatching { Feishu.members(chatId) }.getOrDefault(emptyList()).map {
            if (it.openId != me) it.name
            else if (call.isNotBlank() && call != it.name) "$call（飞书名 ${it.name}，就是他自己）" else "${it.name}（就是他自己）"
        }
        memberCache = memberCache + (chatId to (System.currentTimeMillis() to l))
        return l
    }

    private fun group(name: String): Feishu.Chat {
        val l = groups()
        return l.firstOrNull { it.name == name } ?: l.firstOrNull { it.name.contains(name) || name.contains(it.name) }
            ?: throw Feishu.FsError("没找到群「$name」，CodeX 得先在那个群里")
    }

    private fun rec(id: String?): Rec? = id?.takeIf { it.isNotBlank() }?.let { Store.get(it) }

    /** 卡片上显示的：标题一行 + 内容 */
    fun describe(a: Act): Pair<String, String> {
        val g = a.args.optString("group")
        val r = rec(a.args.optString("meeting"))
        return when (a.kind) {
            "send_to_group" -> "发到「$g」" to (a.args.optString("text") + if (r != null) "\n（附：${r.title} · 纪要）" else "")
            "send_clip" -> "发原话录音到「$g」" to "${r?.title ?: "这场会"} · ${a.args.optString("start")} 起 ${a.args.optInt("seconds", 30).coerceIn(5, 60)} 秒"
            "send_link" -> "发链接到「$g」" to "${r?.title ?: "这场会"} · ${if (a.args.optString("doc") == "transcript") "转写稿" else "纪要"}"
            "create_task" -> "建飞书任务" to (a.args.optString("summary") + a.args.optString("due").takeIf { it.isNotBlank() }?.let { "\n截止 $it" }.orEmpty())
            else -> a.kind to a.args.toString()
        }
    }

    /** 「15:23」「1:02:30」「[15:23]」→ 毫秒 */
    fun timeOf(s: String): Long? {
        val p = s.trim().trim('[', ']').split(':').map { it.trim().toIntOrNull() ?: return null }
        return when (p.size) {
            2 -> (p[0] * 60L + p[1]) * 1000
            3 -> (p[0] * 3600L + p[1] * 60 + p[2]) * 1000
            else -> null
        }
    }

    private fun dueOf(s: String): Long {
        if (s.isBlank()) return 0
        return runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(s.trim())!!.time }.getOrNull()
            ?: runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(s.trim())!!.time + 18 * 3600_000L }.getOrDefault(0L)
    }

    /** 真去做：返回一句结果 */
    fun run(a: Act): String {
        if (!Prefs.feishu.value.ready) throw Feishu.FsError("先在「我的」里接上飞书")
        return when (a.kind) {
            "send_to_group" -> {
                val g = group(a.args.optString("group"))
                val text = a.args.optString("text").trim()
                val r = rec(a.args.optString("meeting"))
                if (r != null) FeishuPush.toGroup(r, g.id, text) else FeishuPush.textToGroup(g.id, text)
                "已发到「${g.name}」"
            }
            "send_link" -> {
                val g = group(a.args.optString("group"))
                val r = rec(a.args.optString("meeting")) ?: throw Feishu.FsError("没找到这场会")
                val cur = FeishuPush.prepareGroup(r, g.id)
                val transcript = a.args.optString("doc") == "transcript"
                val url = if (transcript) cur.fs.transcriptUrl.also { runCatching { Feishu.grantChat(cur.fs.transcriptDoc, "docx", g.id) } } else cur.fs.minutesUrl
                if (url.isBlank()) throw Feishu.FsError("这场会还没存到飞书")
                val label = "${cur.title} · ${if (transcript) "转写稿" else "纪要"}"
                Feishu.send(
                    "chat_id", g.id, "post",
                    JSONObject().put("zh_cn", JSONObject().put("title", "").put("content", JSONArray().put(JSONArray().put(JSONObject().put("tag", "a").put("text", label).put("href", url))))),
                )
                "已发到「${g.name}」"
            }
            "send_clip" -> {
                val g = group(a.args.optString("group"))
                val r = rec(a.args.optString("meeting")) ?: throw Feishu.FsError("没找到这场会")
                val at = timeOf(a.args.optString("start")) ?: throw Feishu.FsError("时间点没看懂：${a.args.optString("start")}")
                val dur = FeishuPush.sendClip(r, g.id, at, a.args.optInt("seconds", 30) * 1000L)
                "已发到「${g.name}」，${dur / 1000} 秒"
            }
            "create_task" -> {
                val r = rec(a.args.optString("meeting"))
                val desc = if (r != null) "${SimpleDateFormat("M月d日", Locale.CHINA).format(r.startedAt)}「${r.title}」" +
                    (if (r.fs.minutesUrl.isNotBlank()) "\n纪要：${r.fs.minutesUrl}" else "") else ""
                Feishu.createTask(a.args.optString("summary"), desc, dueOf(a.args.optString("due")), FeishuSync.taskList())
                "已建好，在飞书任务「会议待办」里"
            }
            else -> throw Feishu.FsError("不认识的动作")
        }
    }
}
