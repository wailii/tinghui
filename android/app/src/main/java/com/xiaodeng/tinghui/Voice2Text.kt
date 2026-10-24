package com.xiaodeng.tinghui

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 替你往外写字：群里的会后消息、催别人的话。
 * 先从你在会上说过的话里总结你的说话习惯，写的时候照着这个味道写，不要像机器写的
 */
object Outbound {
    /** 你在各场会上说的话（声纹认出是你的那个说话人） */
    fun mySentences(limit: Int = 400): List<String> {
        val me = Voice.me() ?: return emptyList()
        val out = ArrayList<String>()
        for (r in Store.recs.value.filter { it.status == Status.Done }) {
            val idx = r.speakers.filter { it.personId == me.id }.map { it.idx }.toSet()
            if (idx.isEmpty()) continue
            Store.sentences(r.id).filter { it.spk in idx && it.text.length >= 8 }.forEach { out += it.text.trim() }
            if (out.size >= limit * 3) break
        }
        // 太短的口头禅和太长的长篇都不要，挑中间的
        return out.filter { it.length in 10..90 }.distinct().shuffled(java.util.Random(7)).take(limit)
    }

    private const val STYLE_SYS = """下面是同一个人在工作会议上说的话，是语音转写稿，有口语、重复和错别字。
他要用这些话的味道，在飞书上给同事写工作消息。总结他说话的习惯，给帮他写消息的人看。

只输出下面这个格式，前后不要加别的话：
习惯：
- 一条一个习惯，写具体：常用的开头、语气词、称呼别人的方式、句子长短、怎么提要求、怎么说时间、会不会客套。最多 7 条
例句：
- 从原话里挑 8 句最像他、又适合写进工作消息的，改掉错别字和重复的口头禅，意思和语气不变"""

    /** 重新总结说话习惯；你说过的话太少（不到 30 句）就不做 */
    fun rebuildStyle(): Prefs.VoiceStyle? {
        val s = mySentences()
        if (s.size < 30) return null
        val out = Llm.chat(STYLE_SYS, s.joinToString("\n"))
        val habits = out.substringAfter("习惯：", "").substringBefore("例句：").trim()
        val examples = out.substringAfter("例句：", "").lines().map { it.trim().removePrefix("-").removePrefix("·").trim() }
            .filter { it.length >= 4 }.take(10)
        if (habits.isBlank()) return null
        return Prefs.VoiceStyle(habits, examples, s.size, System.currentTimeMillis()).also { Prefs.setVoiceStyle(it) }
    }

    /** 说的话比上次总结时多出一半以上就重新总结 */
    fun refreshStyleIfStale() {
        val v = Prefs.voiceStyle.value
        val n = mySentences().size
        if (n >= 30 && (v.habits.isBlank() || n >= v.count * 3 / 2)) runCatching { rebuildStyle() }
    }

    private fun myName() = Voice.me()?.name ?: BuildConfig.FEISHU_USER_NAME.ifBlank { "我" }

    /** 写字时的规矩：像本人在飞书里随手打的，不像机器写的 */
    private fun voiceRules(): String {
        val v = Prefs.voiceStyle.value
        return buildString {
            if (v.habits.isNotBlank()) {
                append("${myName()}平时说话的习惯：\n${v.habits}\n")
                if (v.examples.isNotEmpty()) append("他说过的话（照这个味道写）：\n" + v.examples.joinToString("\n") { "- $it" } + "\n")
                append("\n")
            }
            append(
                """写法：
- 像本人在飞书里打字发出来的，不能像机器或公文。短句，口语，句尾不用每句都加句号。
- 不打招呼不寒暄，不写「各位好」「大家好」「同步一下」「辛苦了」「感谢」「如有问题随时沟通」「以下是」「综上」，除非上面的例句里他自己就这么说。
- 不加粗，不用小标题，不用表情和符号装饰，不用「首先、其次、最后」。几件事就分几行，可以用 1. 2. 编号。
- 一行只说一件事，30 个字以内，不要用分号把几件事塞进一行。日期写「周五」「22 号」，不写「截止10月22日」。
- 只写会上真说过的，不总结、不评价、不补充建议、不提「纪要」「AI」「自动」。
- 写到${myName()}自己时用「我」，不要写自己的名字。
- 人名照纪要里的写。没认出名字的「说话人 1」「说话人 2」这种不要写进去：省掉，或者只说事不说人。"""
            )
        }
    }

    /**
     * 会后发到群里的那条消息：标准格式，只写结论、待办、待定，不模仿谁说话。
     * kind：""=结论和待办，"todos"=只发待办，"decided"=只发结论
     */
    fun groupMessage(r: Rec, kind: String = ""): String {
        val date = SimpleDateFormat("M月d日", Locale.CHINA).format(r.startedAt)
        val parts = when (kind) {
            "todos" -> "只写「待办」一段。"
            "decided" -> "只写「结论」一段。"
            else -> "写「结论」「待办」「待定」三段，哪段没内容就整段不写。"
        }
        val sys = """你在整理一条要发到飞书项目群的会后同步，读的人可能没参会。内容只来自纪要，不编、不评价、不加建议。
$parts

格式：
【${r.title}】会后同步（$date）
结论
1. 一条结论
待办
· 负责人：要做的事（截止时间，会上说了才写）
待定
· 还没定的事

规则：
- 每条一行，不超过 30 个字，写清楚是什么事，不写原因和讨论过程。
- 结论最多 5 条，待办最多 8 条，待定最多 3 条，挑要紧的。
- 人名照纪要写；没认出名字的「说话人 1」这种不写，待办就只写事。写到${myName()}自己时也写名字。
- 书面、简洁，不用口语和语气词，不加粗，不用表情，不写开场白和结尾。

只输出这条消息本身。"""
        val user = "纪要：\n" + Chart.flatten(Store.minutes(r)).take(9000)
        return clean(Llm.chat(sys, user))
    }

    /** 按你说的要求改这条群消息：在你改过的字上改，不从头重写 */
    fun reviseGroupMessage(r: Rec, current: String, ask: String): String {
        val sys = "你在改一条要发到飞书项目群的会后同步。按要求改，没要求改的地方保持原样和原格式；书面、简洁，不加粗，不用表情。\n\n只输出改好的消息本身。"
        val user = "他的要求：$ask\n\n现在的消息：\n$current\n\n会议纪要（需要补内容时从这里找，不要编）：\n" + Chart.flatten(Store.minutes(r)).take(6000)
        return clean(Llm.chat(sys, user))
    }

    /** 催别人的一句话：你复制了自己去发 */
    fun nudge(r: Rec, t: Todo, style: String = ""): String {
        val date = SimpleDateFormat("M月d日", Locale.CHINA).format(r.startedAt)
        val shape = when (style) {
            "polite" -> "语气客气一点，但别客套。"
            "short" -> "一两句就够。"
            "quote" -> "把他会上的原话带上一句。"
            else -> "两三句。"
        }
        val sys = "你替${myName()}给同事${t.owner}写一条飞书私聊，问他会上答应的事进展怎么样。$shape\n\n" + voiceRules() + "\n\n只输出这条消息本身。"
        val sents = Store.sentences(r.id)
        val near = if (t.at >= 0) sents.filter { it.begin in (t.at - 20_000)..(t.at + 20_000) }.joinToString("") { it.text }.take(300) else ""
        val user = "会议：${r.title}（$date）\n这件事：${t.text}\n截止：${t.dueText.ifBlank { "会上没说" }}" +
            if (near.isNotBlank()) "\n会上当时说的：$near" else ""
        return clean(Llm.chat(sys, user))
    }

    private fun clean(s: String) = s.trim().trim('"', '“', '”', '「', '」').replace("**", "")
}
