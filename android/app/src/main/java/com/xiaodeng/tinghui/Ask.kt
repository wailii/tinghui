package com.xiaodeng.tinghui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 小灯：选几场会，拿它们的纪要（放得下就连转写稿一起）给大模型，接着问；
 * 还能让它发到飞书群、建飞书任务（先出卡片，你确认了才做，见 Agent）。
 * 每次对话存一个文件 files/chats/<id>.json
 */
object Ask {
    data class Msg(val role: String, val text: String, val at: Long, val error: Boolean = false, val acts: List<Agent.Act> = emptyList())
    data class Chat(val id: String, val recIds: List<String>, val msgs: List<Msg>, val updatedAt: Long) {
        val question get() = msgs.firstOrNull { it.role == "user" }?.text ?: ""
    }

    private lateinit var dir: File
    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "chats").apply { mkdirs() }
        _chats.value = dir.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { f ->
            runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
        }.map { c ->
            // 上次答到一半 App 被杀了：补一条，好点重试
            if (c.msgs.lastOrNull()?.role == "user") c.copy(msgs = c.msgs + Msg("assistant", "上次没答完", c.updatedAt, error = true)) else c
        }.sortedByDescending { it.updatedAt }
    }

    fun get(id: String) = _chats.value.firstOrNull { it.id == id }

    fun newId() = "c${System.currentTimeMillis()}"

    /** 还没问过的新对话：第一次问的时候才存 */
    fun draft(id: String, recIds: List<String>) = Chat(id, recIds, emptyList(), System.currentTimeMillis())

    fun save(c: Chat) = synchronized(this) {
        if (c.msgs.isEmpty()) return
        val tmp = File(dir, "${c.id}.json.tmp")
        tmp.writeText(toJson(c).toString())
        tmp.renameTo(File(dir, "${c.id}.json"))
        _chats.value = (_chats.value.filter { it.id != c.id } + c).sortedByDescending { it.updatedAt }
    }

    fun delete(id: String) = synchronized(this) {
        File(dir, "$id.json").delete()
        _chats.value = _chats.value.filter { it.id != id }
    }

    private fun toJson(c: Chat) = JSONObject()
        .put("id", c.id).put("updatedAt", c.updatedAt)
        .put("recs", JSONArray(c.recIds))
        .put("msgs", JSONArray(c.msgs.map {
            JSONObject().put("role", it.role).put("text", it.text).put("at", it.at).put("error", it.error)
                .put("acts", JSONArray(it.acts.map { a -> a.toJson() }))
        }))

    private fun fromJson(o: JSONObject) = Chat(
        o.getString("id"),
        o.optJSONArray("recs")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        o.optJSONArray("msgs").objs().map {
            Msg(it.optString("role"), it.optString("text"), it.optLong("at"), it.optBoolean("error"), it.optJSONArray("acts").objs().map { a -> Agent.Act.fromJson(a) })
        },
        o.optLong("updatedAt"),
    )

    /** 转写稿总字数在这以内就一起给模型，回答能引原话；超了只给纪要，没纪要的会按这个总数分着给转写稿 */
    private const val FULL_LIMIT = 60_000

    /** 太长的转写稿留开头和结尾 */
    private fun clip(t: String, n: Int) = if (t.length <= n) t else t.take(n * 2 / 3) + "\n……（中间略）……\n" + t.takeLast(n / 3)

    /** groups：CodeX 在的群；null 表示没接飞书，不给它发群、建任务的本事 */
    private fun system(recs: List<Rec>, groups: List<String>?): String {
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        val texts = recs.associate { r ->
            r.id to Exporter.paragraphs(Store.sentences(r.id)).joinToString("\n") { p ->
                val name = r.speakers.firstOrNull { it.idx == p.spk }?.let { Store.speakerName(it) } ?: "说话人 ${p.spk + 1}"
                "[${clock(p.begin)}] $name：${p.text}"
            }
        }
        val full = texts.values.sumOf { it.length } <= FULL_LIMIT
        val bare = recs.count { it.summary.isBlank() }.coerceAtLeast(1)
        val me = Voice.me()?.name
        return buildString {
            append("你帮一个产品经理回顾他开过的会")
            if (me != null) append("（他叫$me）")
            append("。下面是他选的 ${recs.size} 场会")
            append(if (full) "的纪要和转写稿" else "的纪要")
            append("。回答他的问题：\n")
            append("- 只根据这些材料回答。材料里没有的直接说会上没提到，不要猜。\n")
            append("- 说到具体内容时带上出处：哪场会、什么时间，比如（资源概览页评审 [12:35]）。只有一场会时只写时间。\n")
            append("- 短句，直接说事。不铺垫，不总结，不加粗，不用「综上」「总的来说」「值得注意的是」。分几块回答时每块用 ### 小标题。\n")
            append("- 要逐项列或对比的，比如按人列待办、几场会的说法对比，用 Markdown 表格，整列都空的列不要放。\n")
            append("\n${Chart.PROMPT}\n")
            if (groups != null) {
                append("\n你还能替他做事：发到飞书群（send_to_group）、把纪要或转写稿链接发到群（send_link）、把会上某段原话的录音发到群（send_clip）、给他自己建飞书任务（create_task）。\n")
                append("- 要发原话录音：从转写稿里找到那句话所在行开头的 [mm:ss] 当 start，一段话通常 20～40 秒；他说的「结论」「那句」对应转写稿里说出它的地方。\n")
                append("- 他让你发、建的时候就直接调用，App 会把动作做成卡片给他点确认，卡片就是确认：不要再用文字问他「要不要发」「确认的话我就发」，也不要说「已经发了」，一句话说你准备怎么做就行。他这句话里明确说不用确认、直接发时，动作里带 direct=true。\n")
                append("- 发到群里的会后同步照这个格式：第一行「【会名】会后同步（M月D日）」；「结论」一段用 1. 2. 编号，最多 5 条；「待办」一段每条「· 负责人：事（截止时间）」，最多 8 条；「待定」最多 3 条。每条一行、不超过 30 个字，不写原因和讨论过程。没认出名字的「说话人 N」不写。书面、简洁，不加粗，不用表情。\n")
                append("- 只发他说要发的那部分；只写会上真说过的。\n")
                append("- 现在是 ${SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(System.currentTimeMillis())}，算截止时间以这个为准。\n")
                append("- 要 @ 人：在要发的话里写「@名字」，名字照下面成员名单里的写；不在那个群里的人 @ 不了，就直接告诉他。\n")
                append("- 可用的群：${groups.joinToString("；").ifBlank { "（CodeX 还没进任何群，发群前先让他把 CodeX 拉进群）" }}\n")
            }
            recs.forEachIndexed { i, r ->
                val names = r.speakers.sortedByDescending { it.talkMs }.joinToString("、") { Store.speakerName(it) }
                append("\n========\n第 ${i + 1} 场：${r.title}（编号 ${r.id}，${fmt.format(r.startedAt)}，${durationText(r.durationMs)}，说话人：$names）\n")
                val m = Chart.flatten(Store.minutes(r))
                if (m.isNotBlank()) append("\n纪要：\n$m\n")
                if (full) append("\n转写稿：\n${texts[r.id].orEmpty()}\n")
                else if (m.isBlank()) append("\n转写稿：\n${clip(texts[r.id].orEmpty(), FULL_LIMIT / bare)}\n")
            }
        }
    }

    private fun durationText(ms: Long): String {
        val m = (ms + 30_000) / 60_000
        return if (m < 60) "$m 分钟" else "${m / 60} 小时 ${m % 60} 分"
    }

    /** 后台问：离开问答页也接着写完，写好存下 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _live = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 正在回答的对话 → 已经写出来的部分 */
    val live: StateFlow<Map<String, String>> = _live
    private val _think = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 正在回答的对话 → 模型还没开始写时在想什么 */
    val think: StateFlow<Map<String, String>> = _think

    fun send(c: Chat, question: String) {
        val now = System.currentTimeMillis()
        val withQ = c.copy(msgs = c.msgs + Msg("user", question, now), updatedAt = now)
        save(withQ)
        _live.update { it + (c.id to "") }
        scope.launch {
            try {
                val r = runCatching {
                    ask(c, question, onThink = { t -> _think.update { it + (c.id to t) } }) { t ->
                        _live.update { m -> if (c.id in m) m + (c.id to t) else m }
                    }
                }
                // 回答期间这段对话被删了：结果不要了
                val cur = get(c.id) ?: return@launch
                val reply = r.getOrNull()
                val at = System.currentTimeMillis()
                val acts = reply?.calls.orEmpty().mapIndexed { i, c ->
                    Agent.Act("a${at}_$i", c.name, runCatching { JSONObject(c.args) }.getOrDefault(JSONObject()))
                }
                // 他说了「不用确认、直接发」的动作直接做
                val direct = acts.filter { it.args.optBoolean("direct") }
                val text = reply?.text?.ifBlank { if (acts.size > direct.size) "这样做可以吗：" else if (acts.isNotEmpty()) "好，这就做：" else "" }
                val msg = Msg("assistant", text?.ifBlank { null } ?: r.exceptionOrNull()?.message ?: "出错了", at, error = reply == null, acts = acts)
                runCatching { save(cur.copy(msgs = cur.msgs + msg, updatedAt = msg.at)) }
                direct.forEach { runAct(c.id, msg.at, it.id) }
            } finally {
                _live.update { it - c.id }
                _think.update { it - c.id }
            }
        }
    }

    /** 没答上来的那一问再问一次 */
    fun retry(c: Chat) {
        if (c.msgs.lastOrNull()?.error != true) return
        val q = c.msgs.getOrNull(c.msgs.size - 2)?.takeIf { it.role == "user" } ?: return
        send(c.copy(msgs = c.msgs.dropLast(2)), q.text)
    }

    /** 问一句：带上之前的问答，边写边回调；接了飞书就带上发群、建任务这几个动作 */
    private fun ask(c: Chat, question: String, onThink: (String) -> Unit, onText: (String) -> Unit): Llm.Reply {
        val recs = c.recIds.mapNotNull { Store.get(it) }
        if (recs.isEmpty()) throw Llm.LlmError("选的会议都被删了")
        // 没答上来的那一问连同报错都不带
        val ok = c.msgs.filterIndexed { i, m -> !m.error && c.msgs.getOrNull(i + 1)?.error != true }
        // 之前提过的动作做没做，也告诉模型
        val turns = ok.map { m ->
            m.role to (m.text + m.acts.joinToString("") { a ->
                "\n（${Agent.describe(a).first}：" + when (a.state) { "done" -> "已完成"; "skip" -> "他取消了"; "fail" -> "没做成"; else -> "等他确认" } + "）"
            })
        } + ("user" to question)
        // 每个群带上成员名单：要 @ 谁、能不能 @ 到，模型自己知道
        val groups = if (Prefs.feishu.value.ready) runCatching {
            Agent.groups().map { g ->
                val m = Agent.memberNames(g.id)
                g.name + when {
                    m.isEmpty() -> ""
                    m.size > 50 -> "（${m.size} 人，人多不列）"
                    else -> m.joinToString("、", "（成员：", "）")
                }
            }
        }.getOrDefault(emptyList()) else null
        val msgs = Llm.messages(system(recs, groups), turns)
        return if (groups != null) Llm.chatTools(msgs, Agent.tools(), onText = onText, onThink = onThink)
        else Llm.Reply(Llm.chat(msgs, onText = onText, onThink = onThink), emptyList())
    }

    /** 点了卡片上的「确认」：在后台做，做完把结果写回卡片 */
    fun runAct(chatId: String, msgAt: Long, actId: String) {
        if (!setAct(chatId, msgAt, actId) { it.copy(state = "run", result = "") }) return
        scope.launch {
            val a = get(chatId)?.msgs?.firstOrNull { it.at == msgAt }?.acts?.firstOrNull { it.id == actId } ?: return@launch
            val r = runCatching { Agent.run(a) }
            setAct(chatId, msgAt, actId) {
                if (r.isSuccess) it.copy(state = "done", result = r.getOrNull().orEmpty())
                else it.copy(state = "fail", result = r.exceptionOrNull()?.message ?: "没做成")
            }
        }
    }

    fun skipAct(chatId: String, msgAt: Long, actId: String) {
        setAct(chatId, msgAt, actId) { it.copy(state = "skip") }
    }

    private fun setAct(chatId: String, msgAt: Long, actId: String, f: (Agent.Act) -> Agent.Act): Boolean = synchronized(this) {
        val c = get(chatId) ?: return false
        save(c.copy(msgs = c.msgs.map { m -> if (m.at != msgAt) m else m.copy(acts = m.acts.map { if (it.id == actId) f(it) else it }) }))
        true
    }
}
