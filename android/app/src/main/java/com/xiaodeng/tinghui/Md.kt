package com.xiaodeng.tinghui

/** 模型写的 Markdown 拆成一块一块：手机上排版和发飞书卡片都用它 */
object Md {
    sealed interface Block
    data class Head(val level: Int, val text: String) : Block
    data class Item(val text: String, val depth: Int) : Block
    data class Para(val text: String) : Block
    data class Quote(val text: String) : Block
    data class Table(val head: List<String>, val rows: List<List<String>>) : Block
    data class Code(val lang: String, val text: String) : Block

    private val HEAD = Regex("""^(#{1,6})\s+(.*)$""")
    private val ITEM = Regex("""^(\s*)(?:[-*+]|\d+[.、)])\s+(.*)$""")
    private val RULE = Regex("""^\s*([-*_])\s*(\1\s*){2,}$""")
    private val SEP = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")

    fun parse(md: String): List<Block> {
        val lines = md.lines()
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i].trimEnd()
            val l = raw.trim()
            when {
                l.isEmpty() || RULE.matches(l) -> i++
                l.startsWith("```") -> {
                    val lang = l.removePrefix("```").trim().lowercase()
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) body += lines[i++]
                    i++
                    out += Code(lang, body.joinToString("\n").trimEnd())
                }
                l.startsWith("|") -> {
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val t = lines[i].trim()
                        if (!SEP.matches(t)) rows += cells(t)
                        i++
                    }
                    table(rows)?.let { out += it }
                }
                else -> {
                    val h = HEAD.find(l)
                    val it = ITEM.find(raw)
                    out += when {
                        h != null -> Head(h.groupValues[1].length, h.groupValues[2].trim().trim('*').trim())
                        it != null -> Item(it.groupValues[2].trim(), (it.groupValues[1].replace("\t", "  ").length / 2).coerceAtMost(3))
                        l.startsWith(">") -> Quote(l.trimStart('>').trim())
                        else -> Para(l)
                    }
                    i++
                }
            }
        }
        return out
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }

    /** 整列都空的列不要；每行补齐成表头那么多列 */
    private fun table(rows: List<List<String>>): Table? {
        if (rows.isEmpty()) return null
        val n = rows.maxOf { it.size }
        val full = rows.map { r -> List(n) { r.getOrElse(it) { "" } } }
        val keep = (0 until n).filter { c -> full.drop(1).any { it[c].isNotBlank() } || full.size == 1 }
        if (keep.isEmpty()) return null
        val pick = full.map { r -> keep.map { r[it] } }
        return Table(pick[0], pick.drop(1))
    }

    /** 去掉加粗记号，给只认纯文字的地方用 */
    fun plain(s: String) = s.replace("**", "").replace("__", "")
}
