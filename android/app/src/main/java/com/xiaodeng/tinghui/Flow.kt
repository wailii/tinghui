package com.xiaodeng.tinghui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 纪要里的 Mermaid 流程图（flowchart / graph）自己画，不用网页：手机上显示和发飞书用同一张图。
 * 只认常用写法：A[文字]、A(文字)、A{判断}、A --> B、A -->|标签| B、A -- 标签 --> B、A & B --> C。
 * 一律从上往下排，手机屏幕窄。
 */
object Flow {
    enum class Shape { Box, Round, Choice }
    class Node(val id: String, var text: String, var shape: Shape, var detail: String = "")
    class Edge(val from: String, val to: String, val label: String)
    class Graph(val nodes: List<Node>, val edges: List<Edge>)

    private val ID = Regex("""[\w一-鿿]+""")
    private val ARROW = Regex("""\s*(?:(?:--|==|-\.)\s*([^\s>|\-][^>|]*?)\s*)?(-->|==>|-\.->|---|===|-\.-|--o|--x)\s*(?:\|([^|]*)\|)?\s*""")
    private val OPEN = listOf("((" to "))", "([" to "])", "[[" to "]]", "[(" to ")]", "{{" to "}}", "[/" to "/]", "[\\" to "\\]", "[" to "]", "(" to ")", "{" to "}", ">" to "]")
    private val SKIP = Regex("""^(classDef|class|style|linkStyle|click|subgraph|end|direction)\b""")

    fun parse(code: String): Graph? {
        val lines = code.lines().map { it.substringBefore("%%").trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || !Regex("^(flowchart|graph)\\b").containsMatchIn(lines[0])) return null
        val nodes = LinkedHashMap<String, Node>()
        val edges = ArrayList<Edge>()
        for (stmt in lines.drop(1).flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (SKIP.containsMatchIn(stmt)) continue
            var pos = 0
            fun node(): String? {
                while (pos < stmt.length && stmt[pos] == ' ') pos++
                val m = ID.matchAt(stmt, pos) ?: return null
                pos = m.range.last + 1
                val id = m.value
                val n = nodes.getOrPut(id) { Node(id, id, Shape.Box) }
                val open = OPEN.firstOrNull { stmt.startsWith(it.first, pos) }
                if (open != null) {
                    val end = stmt.indexOf(open.second, pos + open.first.length)
                    if (end > 0) {
                        n.text = stmt.substring(pos + open.first.length, end).trim().trim('"').replace("<br>", " ").replace("<br/>", " ")
                        n.shape = when (open.first) {
                            "{", "{{" -> Shape.Choice
                            "(", "((", "([" -> Shape.Round
                            else -> Shape.Box
                        }
                        pos = end + open.second.length
                    }
                }
                return id
            }
            fun group(): List<String> {
                val out = ArrayList<String>()
                node()?.let { out += it } ?: return out
                while (true) {
                    val m = Regex("""\s*&\s*""").matchAt(stmt, pos) ?: break
                    pos = m.range.last + 1
                    node()?.let { out += it } ?: break
                }
                return out
            }
            var left = group()
            while (left.isNotEmpty() && pos < stmt.length) {
                val m = ARROW.matchAt(stmt, pos) ?: break
                pos = m.range.last + 1
                val label = (m.groups[1]?.value ?: m.groups[3]?.value ?: "").trim().trim('"')
                val right = group()
                for (a in left) for (b in right) edges += Edge(a, b, label)
                left = right
            }
        }
        if (nodes.isEmpty()) return null
        return Graph(nodes.values.toList(), edges)
    }

    /** Mermaid 原文画成图；画不出来返回 null，调用方退回显示原文 */
    fun render(code: String, widthPx: Int, look: Chart.Look): Bitmap? = parse(code)?.let { render(it, widthPx, look, null) }

    /** 宽度给定，高度按内容算；一层框太多时图会比给的宽 */
    fun render(g: Graph, widthPx: Int, look: Chart.Look, title: String?): Bitmap? {
        if (g.nodes.isEmpty() || g.nodes.size > 30 || widthPx < 100) return null
        val d = look.d
        val st = object {
            val bg = look.bg; val node = look.card; val nodeLine = look.line; val choice = look.accentSoft
            val text = look.ink; val line = look.sub; val label = look.sub
        }
        val pad = 16 * d
        val gapX = 12 * d
        val nodePadX = 12 * d
        val nodePadY = 9 * d
        val maxNodeW = 150 * d

        // 分层：从没有入边的点开始，每个点的层 = 前面最长路径；回头的边不算
        val ids = g.nodes.map { it.id }
        val out = g.edges.groupBy { it.from }
        val back = HashSet<Edge>()
        val state = HashMap<String, Int>()
        fun dfs(u: String) {
            state[u] = 1
            for (e in out[u].orEmpty()) {
                when (state[e.to]) {
                    1 -> back += e
                    null -> dfs(e.to)
                }
            }
            state[u] = 2
        }
        val hasIn = g.edges.map { it.to }.toSet()
        ids.filter { it !in hasIn }.forEach { if (state[it] == null) dfs(it) }
        ids.forEach { if (state[it] == null) dfs(it) }
        val fwd = g.edges.filter { it !in back && it.from != it.to }
        val rank = HashMap<String, Int>().apply { ids.forEach { put(it, 0) } }
        for (round in 0 until ids.size) {
            var changed = false
            for (e in fwd) if (rank[e.to]!! < rank[e.from]!! + 1) {
                rank[e.to] = rank[e.from]!! + 1
                changed = true
            }
            if (!changed) break
        }
        val rowsMap = sortedMapOf<Int, MutableList<String>>()
        ids.forEach { rowsMap.getOrPut(rank[it]!!) { mutableListOf() } += it }

        // 跨好几层的线，在中间每层放一个看不见的点占位，线从空档里穿过去，不压在框上
        val chains = HashMap<Edge, List<String>>()
        val dummy = HashSet<String>()
        for (e in fwd) {
            val r1 = rank[e.from]!!
            val r2 = rank[e.to]!!
            val chain = mutableListOf(e.from)
            for (r in r1 + 1 until r2) {
                val id = "\u0000${dummy.size}"
                dummy += id
                rank[id] = r
                rowsMap.getOrPut(r) { mutableListOf() } += id
                chain += id
            }
            chain += e.to
            chains[e] = chain
        }
        val up = HashMap<String, MutableList<String>>()
        val down = HashMap<String, MutableList<String>>()
        chains.values.forEach { c -> c.zipWithNext { a, b -> up.getOrPut(b) { mutableListOf() } += a; down.getOrPut(a) { mutableListOf() } += b } }
        val rows = rowsMap.values.toList()

        // 同一层里按相邻层的位置排，线少交叉
        fun sweep(i: Int, ref: List<String>, adj: Map<String, List<String>>) {
            val cur = rows[i].toList()
            rows[i].sortBy { id ->
                val ps = adj[id].orEmpty().mapNotNull { n -> ref.indexOf(n).takeIf { it >= 0 } }
                if (ps.isEmpty()) cur.indexOf(id).toDouble() else ps.average()
            }
        }
        repeat(2) {
            for (i in 1 until rows.size) sweep(i, rows[i - 1], up)
            for (i in rows.size - 2 downTo 0) sweep(i, rows[i + 1], down)
        }
        for (i in 1 until rows.size) sweep(i, rows[i - 1], up)

        val hasDetail = g.nodes.any { it.detail.isNotBlank() }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13 * d; color = st.text
            if (hasDetail) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val dp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * d; color = look.sub }
        val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; color = st.label }
        fun layout(t: String, w: Int, p: TextPaint) = StaticLayout.Builder.obtain(t, 0, t.length, p, max(w, 1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.1f).build()

        // 一层框太多时不硬挤：每个框至少这么宽，整张图变宽，手机上左右划
        val minCell = 84 * d
        val dummyW = 6 * d
        val gutter = if (back.isNotEmpty()) 30 * d else 0f
        val textOf = g.nodes.associate { it.id to (if (it.detail.length > it.text.length) it.detail else it.text) }
        val need = rows.maxOf { r ->
            pad * 2 + gapX * (r.size - 1) + gutter + r.sumOf { id ->
                val t = textOf[id]
                (if (t == null) dummyW else min(minCell, tp.measureText(t) + nodePadX * 2)).toDouble()
            }.toFloat()
        }
        val width = max(widthPx.toFloat(), need).toInt()
        val avail = width - pad * 2 - gutter

        class Box(val id: String, val l: StaticLayout?, val w: Float, val h: Float, var x: Float = 0f, var y: Float = 0f, val dl: StaticLayout? = null)
        val boxes = HashMap<String, Box>()
        val rowTop = HashMap<String, Float>()
        val rowBot = HashMap<String, Float>()
        val titleL = title?.takeIf { it.isNotBlank() }?.let {
            Chart.text(it, Chart.paint(look, 14f, look.ink, true), (width - pad * 2).toInt())
        }
        var y = pad + (titleL?.let { it.height + 14 * d } ?: 0f)
        val hasLabel = g.edges.any { it.label.isNotBlank() }
        val gapY = (if (hasLabel) 46 else 34) * d
        for (row in rows) {
            val real = row.count { it !in dummy }
            val cellW = min(maxNodeW, max(minCell, (avail - (row.size - real) * dummyW - gapX * (row.size - 1)) / real))
            val made = row.map { id ->
                if (id in dummy) Box(id, null, dummyW, 0f)
                else {
                    val n = g.nodes.first { it.id == id }
                    // 宽度向上取整再多留 1 像素，免得最后一个字折下去
                    val textW = kotlin.math.ceil(min(tp.measureText(n.text) + 1, cellW - nodePadX * 2)).toInt()
                    val l = layout(n.text, textW, tp)
                    val dl = n.detail.takeIf { it.isNotBlank() }?.let { t -> layout(t, kotlin.math.ceil(min(dp.measureText(t) + 1, cellW - nodePadX * 2)).toInt(), dp) }
                    val w = max(l.width, dl?.width ?: 0) + nodePadX * 2 + if (n.shape == Shape.Choice) 10 * d else 0f
                    Box(id, l, w, l.height + (dl?.let { it.height + 3 * d } ?: 0f) + nodePadY * 2, dl = dl)
                }
            }
            val total = made.sumOf { it.w.toDouble() }.toFloat() + gapX * (row.size - 1)
            var x = pad + (avail - total) / 2
            val rowH = made.maxOf { it.h }
            made.forEach {
                it.x = x
                it.y = y + (rowH - it.h) / 2
                x += it.w + gapX
                boxes[it.id] = it
                rowTop[it.id] = y
                rowBot[it.id] = y + rowH
            }
            y += rowH + gapY
        }
        val height = (y - gapY + pad).toInt()
        val bmp = Bitmap.createBitmap(width, max(height, 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(st.bg)
        titleL?.let {
            c.save()
            c.translate(pad, pad)
            it.draw(c)
            c.restore()
        }

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = st.line
            strokeCap = Paint.Cap.ROUND
        }
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = st.line }
        fun arrow(x: Float, y: Float, ang: Double) {
            val s = 7 * d
            val p = Path()
            p.moveTo(x, y)
            p.lineTo((x - s * cos(ang - 0.45)).toFloat(), (y - s * sin(ang - 0.45)).toFloat())
            p.lineTo((x - s * cos(ang + 0.45)).toFloat(), (y - s * sin(ang + 0.45)).toFloat())
            p.close()
            c.drawPath(p, head)
        }
        val labels = ArrayList<Triple<String, Float, Float>>()
        for (e in g.edges) {
            if (e.from == e.to) continue
            val a = boxes[e.from] ?: continue
            val b = boxes[e.to] ?: continue
            val p = Path()
            val chain = chains[e]
            if (chain == null) {
                // 回头的线：从右边空出来的那条道绕回去
                val x1 = a.x + a.w
                val y1 = a.y + a.h / 2
                val x2 = b.x + b.w
                val y2 = b.y + b.h / 2
                val between = boxes.values.filter { it.y + it.h >= min(y1, y2) && it.y <= max(y1, y2) }
                val bulge = min(width - 6 * d, (between.maxOfOrNull { it.x + it.w } ?: max(x1, x2)) + 20 * d)
                p.moveTo(x1, y1)
                p.cubicTo(bulge, y1, bulge, y2, x2 + 1, y2)
                c.drawPath(p, stroke)
                arrow(x2, y2, Math.PI)
                if (e.label.isNotBlank()) labels += Triple(e.label, bulge - 6 * d, (y1 + y2) / 2)
            } else {
                var px = a.x + a.w / 2
                var py = a.y + a.h
                p.moveTo(px, py)
                var lastMy = py
                for ((k, id) in chain.withIndex()) {
                    if (k == 0) continue
                    val bx = boxes[id]!!
                    val tx = bx.x + bx.w / 2
                    val ty = if (id in dummy) rowTop[id]!! else bx.y
                    val my = (py + ty) / 2
                    p.cubicTo(px, my, tx, my, tx, if (id in dummy) ty else ty - 1)
                    if (k == 1 && e.label.isNotBlank()) labels += Triple(e.label, (px + tx) / 2, my)
                    lastMy = my
                    px = tx
                    py = ty
                    if (id in dummy) {
                        py = rowBot[id]!!
                        p.lineTo(px, py)
                    }
                }
                c.drawPath(p, stroke)
                arrow(px, b.y, atan2((b.y - lastMy).toDouble(), 0.0))
            }
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
        for (n in g.nodes) {
            val b = boxes[n.id] ?: continue
            val l = b.l ?: continue
            val r = RectF(b.x, b.y, b.x + b.w, b.y + b.h)
            when (n.shape) {
                Shape.Choice -> {
                    // 判断：两头尖的六边形，比菱形省地方
                    val k = 9 * d
                    val p = Path().apply {
                        moveTo(r.left, r.centerY()); lineTo(r.left + k, r.top); lineTo(r.right - k, r.top)
                        lineTo(r.right, r.centerY()); lineTo(r.right - k, r.bottom); lineTo(r.left + k, r.bottom); close()
                    }
                    fill.color = st.choice
                    c.drawPath(p, fill)
                }
                Shape.Round -> {
                    fill.color = st.node
                    c.drawRoundRect(r, b.h / 2, b.h / 2, fill)
                    border.color = st.nodeLine
                    c.drawRoundRect(r, b.h / 2, b.h / 2, border)
                }
                Shape.Box -> {
                    fill.color = st.node
                    c.drawRoundRect(r, 9 * d, 9 * d, fill)
                    border.color = st.nodeLine
                    c.drawRoundRect(r, 9 * d, 9 * d, border)
                }
            }
            val dl = b.dl
            val th = l.height + (dl?.let { it.height + 3 * d } ?: 0f)
            c.save()
            c.translate(b.x + (b.w - l.width) / 2, b.y + (b.h - th) / 2)
            l.draw(c)
            c.restore()
            if (dl != null) {
                c.save()
                c.translate(b.x + (b.w - dl.width) / 2, b.y + (b.h - th) / 2 + l.height + 3 * d)
                dl.draw(c)
                c.restore()
            }
        }
        for ((t, x, ly) in labels) {
            val l = layout(t, kotlin.math.ceil(min(lp.measureText(t) + 1, 110 * d)).toInt(), lp)
            val r = RectF(x - l.width / 2f - 4 * d, ly - l.height / 2f - 2 * d, x + l.width / 2f + 4 * d, ly + l.height / 2f + 2 * d)
            fill.color = st.bg
            c.drawRoundRect(r, 4 * d, 4 * d, fill)
            c.save()
            c.translate(x - l.width / 2f, ly - l.height / 2f)
            l.draw(c)
            c.restore()
        }
        return bmp
    }

    /** 飞书用：2 倍清晰度 */
    fun png(code: String): ByteArray? = render(code, 720, Chart.Look.feishu())?.let { Chart.toPng(it) }

    /** 画不出图时的文字版：一行一条线 */
    fun text(code: String): String {
        val g = parse(code) ?: return code
        val name = g.nodes.associate { it.id to it.text }
        return g.edges.joinToString("\n") { e ->
            "${name[e.from]} → ${if (e.label.isNotBlank()) "（${e.label}）" else ""}${name[e.to]}"
        }
    }
}
