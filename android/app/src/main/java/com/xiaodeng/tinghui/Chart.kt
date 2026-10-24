package com.xiaodeng.tinghui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 纪要和问答里的图：模型挑一种图、把内容填成 JSON（```chart 包着），这里统一画。
 * 手机上显示和发飞书用同一张图。
 * flow 步骤 · timeline 时间线 · tree 拆解 · compare 方案对比 · bars 数字对比 · graph 关系
 */
object Chart {
    /** 颜色和密度；和 App 的配色一致 */
    class Look(
        val d: Float,
        val bg: Int = 0xFFF6F5F1.toInt(),
        val card: Int = 0xFFFFFFFF.toInt(),
        val line: Int = 0xFFE6E4DE.toInt(),
        val ink: Int = 0xFF121212.toInt(),
        val sub: Int = 0xFF8C8A84.toInt(),
        val hint: Int = 0xFFC9C7C0.toInt(),
        val accent: Int = 0xFFFF5A1F.toInt(),
        val accentSoft: Int = 0xFFFFEDE4.toInt(),
        val accentText: Int = 0xFFB0573A.toInt(),
        val good: Int = 0xFF1F9D55.toInt(),
        val risk: Int = 0xFFE5484D.toInt(),
    ) {
        companion object {
            fun feishu() = Look(2f)
        }
    }

    /** 告诉模型什么时候画、怎么填（纪要和问答都用） */
    const val PROMPT = """图：
- 会上讲了步骤流程、一件事怎么拆、时间节点、几个方案取舍、一组能比的数字，就画成图，放在讲这件事的那一节里。一份纪要一般 1 到 2 张，最多 3 张。闲聊、只有一两句话的事不画。
- 用 ```chart 包一段 JSON，type 选最合适的一种：
  flow 有先后的步骤或链路：{"type":"flow","title":"图标题","steps":[{"title":"短标题","detail":"一句话：谁、做什么、多少","who":"负责人","state":"done"}]}
  timeline 有日期或时间节点：{"type":"timeline","title":"…","items":[{"time":"下周一","title":"…","detail":"…","who":"…","state":"todo"}]}
  tree 一件事拆成几块：{"type":"tree","title":"…","root":{"title":"…","detail":"…","children":[{"title":"…","detail":"…","children":[{"title":"…","detail":"…"}]}]}}
  compare 几个方案的取舍：{"type":"compare","title":"…","options":[{"name":"…","pros":["…"],"cons":["…"],"note":"…","picked":true}]}
  bars 一组能比大小的数字：{"type":"bars","title":"…","unit":"万元","items":[{"label":"…","value":700,"note":"…"}]}
  graph 几样东西互相依赖、不是一条线：{"type":"graph","title":"…","nodes":[{"id":"a","title":"…","detail":"…"}],"edges":[{"from":"a","to":"b","label":"…"}]}
- 每个框都写短标题加一句说明，说明里放会上说的具体内容：谁、做什么、数字带单位。不要只写名词。
- state 只用这几个：done 已定或已做，todo 要做，open 没定，risk 有风险；说不上就不写。图里不写时间戳。
- 图里写过的，正文不再一条条重复。"""

    enum class State(val label: String) { Done("已定"), Todo("要做"), Open("没定"), Risk("有风险"), None("") }

    class Step(val title: String, val detail: String, val who: String, val state: State, val time: String = "")
    class TNode(val title: String, val detail: String, val children: List<TNode>)
    class Opt(val name: String, val pros: List<String>, val cons: List<String>, val note: String, val picked: Boolean)
    class Bar(val label: String, val value: Double, val note: String)

    sealed class Spec(val title: String)
    class FlowSpec(title: String, val steps: List<Step>, val timeline: Boolean) : Spec(title)
    class TreeSpec(title: String, val root: TNode) : Spec(title)
    class CompareSpec(title: String, val options: List<Opt>) : Spec(title)
    class BarsSpec(title: String, val unit: String, val bars: List<Bar>) : Spec(title)
    class GraphSpec(title: String, val graph: Flow.Graph) : Spec(title)

    // ---------- 解析 ----------

    private val TIME = Regex("""\s*\[\d{1,2}:\d{2}(?::\d{2})?]""")
    private fun JSONObject.s(k: String) = optStr(k)?.replace(TIME, "")?.trim() ?: ""
    private fun JSONArray?.strs() = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).replace(TIME, "").trim().ifBlank { null } }

    private fun state(s: String) = when (s.lowercase()) {
        "done", "已定", "已做", "完成" -> State.Done
        "todo", "要做", "待办" -> State.Todo
        "open", "没定", "待定" -> State.Open
        "risk", "风险", "有风险" -> State.Risk
        else -> State.None
    }

    fun parse(json: String): Spec? = runCatching {
        // 模型偶尔多写个逗号
        val o = JSONObject(json.trim().replace(Regex(""",\s*([}\]])"""), "$1"))
        val title = o.s("title")
        when (o.optString("type").lowercase()) {
            "flow", "timeline" -> {
                val steps = (o.optJSONArray("steps") ?: o.optJSONArray("items")).objs().take(12).map {
                    Step(it.s("title"), it.s("detail"), it.s("who"), state(it.s("state")), it.s("time"))
                }.filter { it.title.isNotBlank() }
                if (steps.isEmpty()) null
                else FlowSpec(title, steps, o.optString("type") == "timeline" || steps.any { it.time.isNotBlank() })
            }
            "tree" -> {
                fun node(n: JSONObject, depth: Int): TNode = TNode(
                    n.s("title"), n.s("detail"),
                    if (depth >= 2) emptyList() else n.optJSONArray("children").objs().take(10).map { node(it, depth + 1) }.filter { it.title.isNotBlank() },
                )
                o.optJSONObject("root")?.let { node(it, 0) }?.takeIf { it.title.isNotBlank() && it.children.isNotEmpty() }?.let { TreeSpec(title, it) }
            }
            "compare" -> o.optJSONArray("options").objs().take(4).map {
                Opt(it.s("name"), it.optJSONArray("pros").strs(), it.optJSONArray("cons").strs(), it.s("note"), it.optBoolean("picked"))
            }.filter { it.name.isNotBlank() }.takeIf { it.size >= 2 }?.let { CompareSpec(title, it) }
            "bars" -> o.optJSONArray("items").objs().take(12).mapNotNull {
                val v = it.optDouble("value", Double.NaN)
                if (v.isNaN() || it.s("label").isBlank()) null else Bar(it.s("label"), v, it.s("note"))
            }.takeIf { it.size >= 2 }?.let { BarsSpec(title, o.s("unit"), it) }
            "graph" -> {
                val nodes = o.optJSONArray("nodes").objs().take(14).mapNotNull {
                    val id = it.s("id").ifBlank { it.s("title") }
                    if (id.isBlank()) null else Flow.Node(id, it.s("title").ifBlank { id }, Flow.Shape.Box, it.s("detail"))
                }
                val ids = nodes.map { it.id }.toSet()
                val edges = o.optJSONArray("edges").objs().mapNotNull {
                    val f = it.s("from")
                    val t = it.s("to")
                    if (f in ids && t in ids) Flow.Edge(f, t, it.s("label")) else null
                }
                if (nodes.size < 2) null else GraphSpec(title, Flow.Graph(nodes, edges))
            }
            else -> null
        }
    }.getOrNull()

    private val BLOCK = Regex("```(chart|json)[ \t]*\n(.*?)\n?```", RegexOption.DOT_MATCHES_ALL)

    /** 复制、导出、喂给模型时：图换成文字版；画不出来的 chart 块去掉，json 块原样留着 */
    fun flatten(md: String): String = md.replace(BLOCK) { m ->
        parse(m.groupValues[2])?.let { plain(it) } ?: if (m.groupValues[1] == "json") m.value else ""
    }

    /** 发不了图时的文字版 */
    fun plain(s: Spec): String = buildString {
        if (s.title.isNotBlank()) append("**${s.title}**\n")
        when (s) {
            is FlowSpec -> s.steps.forEachIndexed { i, st ->
                append(if (s.timeline && st.time.isNotBlank()) "${st.time}  " else "${i + 1}. ")
                append(st.title)
                if (st.detail.isNotBlank()) append("：${st.detail}")
                if (st.who.isNotBlank()) append("（${st.who}）")
                if (st.state != State.None) append(" · ${st.state.label}")
                append('\n')
            }
            is TreeSpec -> {
                append("${s.root.title}${if (s.root.detail.isNotBlank()) "：${s.root.detail}" else ""}\n")
                s.root.children.forEach { c ->
                    append("- ${c.title}${if (c.detail.isNotBlank()) "：${c.detail}" else ""}\n")
                    c.children.forEach { g -> append("    - ${g.title}${if (g.detail.isNotBlank()) "：${g.detail}" else ""}\n") }
                }
            }
            is CompareSpec -> s.options.forEach { o ->
                append("- ${o.name}${if (o.picked) "（选了这个）" else ""}\n")
                o.pros.forEach { append("    + $it\n") }
                o.cons.forEach { append("    - $it\n") }
            }
            is BarsSpec -> s.bars.forEach { append("- ${it.label}：${num(it.value)}${s.unit}\n") }
            is GraphSpec -> {
                val name = s.graph.nodes.associate { it.id to it.text }
                s.graph.edges.forEach { e -> append("${name[e.from]} → ${if (e.label.isNotBlank()) "（${e.label}）" else ""}${name[e.to]}\n") }
            }
        }
    }.trim()

    private fun num(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString() else "%.1f".format(v)

    // ---------- 画 ----------

    fun paint(look: Look, sp: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp * look.d
        this.color = color
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun text(t: CharSequence, p: TextPaint, w: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, p, max(w, 1)).setAlignment(align).setLineSpacing(0f, 1.12f).build()

    fun toPng(b: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        b.recycle()
        return out.toByteArray()
    }

    /** 飞书用：2 倍清晰度，360dp 宽 */
    fun png(json: String): ByteArray? = parse(json)?.let { render(it, 720, Look.feishu()) }?.let { toPng(it) }

    private fun StaticLayout.at(c: Canvas, x: Float, y: Float) {
        c.save()
        c.translate(x, y)
        draw(c)
        c.restore()
    }

    /** 先量高度再画：每种图返回总高度和画法 */
    private class Plan(val h: Float, val draw: (Canvas) -> Unit)

    fun render(s: Spec, widthPx: Int, look: Look): Bitmap? {
        if (widthPx < 100) return null
        if (s is GraphSpec) return Flow.render(s.graph, widthPx, look, s.title)
        val d = look.d
        val pad = 16 * d
        val w = widthPx.toFloat()
        val titleL = s.title.takeIf { it.isNotBlank() }?.let { text(it, paint(look, 14f, look.ink, true), (w - pad * 2).toInt()) }
        val top = pad + (titleL?.let { it.height + 14 * d } ?: 0f)
        val plan = when (s) {
            is FlowSpec -> rail(s, look, pad, top, w)
            is TreeSpec -> tree(s, look, pad, top, w)
            is CompareSpec -> compare(s, look, pad, top, w)
            is BarsSpec -> bars(s, look, pad, top, w)
            is GraphSpec -> return null
        }
        val bmp = Bitmap.createBitmap(widthPx, (plan.h + pad).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(look.bg)
        titleL?.at(c, pad, pad)
        plan.draw(c)
        return bmp
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = width }

    private fun card(c: Canvas, look: Look, r: RectF, border: Int = look.line, dashed: Boolean = false, bg: Int = look.card) {
        val d = look.d
        c.drawRoundRect(r, 12 * d, 12 * d, fill(bg))
        val p = stroke(border, 1f * d)
        if (dashed) p.pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
        c.drawRoundRect(r, 12 * d, 12 * d, p)
    }

    /** 负责人小标签：橙色浅底 */
    private class Chip(val l: StaticLayout, val w: Float, val h: Float)
    private fun chip(look: Look, t: String, maxW: Float = 200 * look.d): Chip? {
        if (t.isBlank()) return null
        val d = look.d
        val p = paint(look, 11f, look.accentText, true)
        val l = text(t, p, kotlin.math.ceil(min(p.measureText(t) + 1, maxW - 14 * d)).toInt())
        return Chip(l, l.width + 14 * d, l.height + 6 * d)
    }
    private fun drawChip(c: Canvas, look: Look, ch: Chip, x: Float, y: Float) {
        val d = look.d
        c.drawRoundRect(RectF(x, y, x + ch.w, y + ch.h), ch.h / 2, ch.h / 2, fill(look.accentSoft))
        ch.l.at(c, x + 7 * d, y + 3 * d)
    }

    private fun stateColor(look: Look, s: State) = when (s) {
        State.Done -> look.good
        State.Todo -> look.accent
        State.Risk -> look.risk
        State.Open -> look.hint
        State.None -> look.ink
    }

    /** 状态说明一行：只列出图里用到的 */
    private fun legend(look: Look, states: Set<State>, x: Float, y: Float, w: Float): Plan {
        val used = listOf(State.Done, State.Todo, State.Open, State.Risk).filter { it in states }
        // 只有一种状态时说明没用
        if (used.size < 2) return Plan(0f) {}
        val d = look.d
        val p = paint(look, 11f, look.sub)
        return Plan(10 * d + p.textSize * 1.3f) { c ->
            var cx = x
            val cy = y + 10 * d + p.textSize * 0.65f
            for (s in used) {
                if (s == State.Open) c.drawCircle(cx + 4 * d, cy, 3.5f * d, stroke(look.hint, 1.2f * d))
                else c.drawCircle(cx + 4 * d, cy, 4 * d, fill(stateColor(look, s)))
                c.drawText(s.label, cx + 12 * d, cy + p.textSize * 0.36f, p)
                cx += 12 * d + p.measureText(s.label) + 16 * d
            }
        }
    }

    /** 步骤 / 时间线：左边一条竖线串起来，右边每步一张卡片 */
    private fun rail(s: FlowSpec, look: Look, pad: Float, top: Float, w: Float): Plan {
        val d = look.d
        val timeP = paint(look, 12f, look.ink, true)
        val timeW = if (s.timeline) kotlin.math.ceil(min(s.steps.maxOf { timeP.measureText(it.time) } + 1, 74 * d)) + 10 * d else 0f
        val railX = pad + timeW + 12 * d
        val cardX = railX + 12 * d + 10 * d
        val cardW = w - cardX - pad
        val inner = 12 * d
        val titleP = paint(look, 14f, look.ink, true)
        val detP = paint(look, 12.5f, look.sub)
        // 负责人短就放标题右边；长了单独一行放标题下面，不挤标题
        class Row(val st: Step, val t: StaticLayout, val det: StaticLayout?, val ch: Chip?, val below: Boolean, val time: StaticLayout?, val h: Float)
        val iw = cardW - inner * 2
        val rows = s.steps.map { st ->
            val short = chip(look, st.who, iw)
            val below = short != null && short.w > iw * 0.4f
            val ch = if (below) short else chip(look, st.who, iw * 0.4f)
            val t = text(st.title, titleP, (iw - (if (ch != null && !below) ch.w + 8 * d else 0f)).toInt())
            val det = st.detail.takeIf { it.isNotBlank() }?.let { text(it, detP, iw.toInt()) }
            val time = if (s.timeline && st.time.isNotBlank()) text(st.time, timeP, (timeW - 10 * d).toInt(), Layout.Alignment.ALIGN_OPPOSITE) else null
            val head = if (below) t.height + 6 * d + ch!!.h else max(t.height.toFloat(), ch?.h ?: 0f)
            val h = inner * 2 + head + (det?.let { it.height + 4 * d } ?: 0f)
            Row(st, t, det, ch, below, time, max(h, (time?.height ?: 0) + inner * 2))
        }
        val gap = 10 * d
        val total = rows.sumOf { it.h.toDouble() }.toFloat() + gap * (rows.size - 1)
        val lg = legend(look, rows.map { it.st.state }.toSet(), pad, top + total, w)
        return Plan(top + total + lg.h) { c ->
            var y = top
            val centers = ArrayList<Float>()
            rows.forEach { r -> centers += y + inner + titleP.textSize * 0.62f; y += r.h + gap }
            // 竖线
            if (centers.size > 1) c.drawLine(railX, centers.first(), railX, centers.last(), stroke(look.line, 2 * d))
            y = top
            rows.forEachIndexed { i, r ->
                val cy = centers[i]
                val col = stateColor(look, r.st.state)
                if (s.timeline) {
                    c.drawCircle(railX, cy, 6 * d, fill(look.bg))
                    if (r.st.state == State.Open) c.drawCircle(railX, cy, 5 * d, stroke(look.hint, 1.5f * d))
                    else c.drawCircle(railX, cy, 5 * d, fill(if (r.st.state == State.None) look.accent else col))
                    r.time?.at(c, pad, y + inner)
                } else {
                    val rad = 11 * d
                    if (r.st.state == State.Open) {
                        c.drawCircle(railX, cy, rad, fill(look.card))
                        c.drawCircle(railX, cy, rad, stroke(look.hint, 1.2f * d))
                    } else c.drawCircle(railX, cy, rad, fill(col))
                    val np = paint(look, 11.5f, if (r.st.state == State.Open) look.sub else look.card, true).apply { textAlign = Paint.Align.CENTER }
                    c.drawText("${i + 1}", railX, cy + np.textSize * 0.36f, np)
                }
                val rect = RectF(cardX, y, cardX + cardW, y + r.h)
                card(c, look, rect, border = if (r.st.state == State.Risk) look.risk else look.line, dashed = r.st.state == State.Open)
                r.t.at(c, cardX + inner, y + inner)
                val headH = if (r.below) {
                    drawChip(c, look, r.ch!!, cardX + inner, y + inner + r.t.height + 6 * d)
                    r.t.height + 6 * d + r.ch.h
                } else {
                    r.ch?.let { drawChip(c, look, it, cardX + cardW - inner - it.w, y + inner - 1 * d) }
                    max(r.t.height.toFloat(), r.ch?.h ?: 0f)
                }
                r.det?.at(c, cardX + inner, y + inner + headH + 4 * d)
                y += r.h + gap
            }
            lg.draw(c)
        }
    }

    /** 拆解：顶上一张深色卡片是整件事，下面每块一张卡片，用树枝线连到主干；第三层写在卡片里 */
    private fun tree(s: TreeSpec, look: Look, pad: Float, top: Float, w: Float): Plan {
        val d = look.d
        val inner = 12 * d
        val rootT = text(s.root.title, paint(look, 15f, look.card, true), (w - pad * 2 - inner * 2).toInt())
        val rootD = s.root.detail.takeIf { it.isNotBlank() }?.let { text(it, paint(look, 12.5f, 0xFFB9B7B1.toInt()), (w - pad * 2 - inner * 2).toInt()) }
        val rootH = inner * 2 + rootT.height + (rootD?.let { it.height + 4 * d } ?: 0f)
        val trunkX = pad + 14 * d
        val cardX = trunkX + 16 * d
        val cardW = w - cardX - pad
        val titleP = paint(look, 14f, look.ink, true)
        val detP = paint(look, 12.5f, look.sub)
        class Row(val t: StaticLayout, val det: StaticLayout?, val kids: StaticLayout?, val h: Float)
        val rows = s.root.children.map { n ->
            val t = text(n.title, titleP, (cardW - inner * 2).toInt())
            val det = n.detail.takeIf { it.isNotBlank() }?.let { text(it, detP, (cardW - inner * 2).toInt()) }
            val kids = n.children.takeIf { it.isNotEmpty() }?.let { ks ->
                val sb = SpannableStringBuilder()
                ks.forEachIndexed { i, k ->
                    if (i > 0) sb.append('\n')
                    sb.append("·  ")
                    val a = sb.length
                    sb.append(k.title)
                    sb.setSpan(ForegroundColorSpan(look.ink), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (k.detail.isNotBlank()) {
                        val b = sb.length
                        sb.append("  ${k.detail}")
                        sb.setSpan(AbsoluteSizeSpan((12 * d).toInt()), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                text(sb, paint(look, 13f, look.sub), (cardW - inner * 2).toInt())
            }
            val h = inner * 2 + t.height + (det?.let { it.height + 4 * d } ?: 0f) + (kids?.let { it.height + 10 * d } ?: 0f)
            Row(t, det, kids, h)
        }
        val gap = 10 * d
        val startY = top + rootH + 14 * d
        val total = rows.sumOf { it.h.toDouble() }.toFloat() + gap * (rows.size - 1)
        return Plan(startY + total) { c ->
            c.drawRoundRect(RectF(pad, top, w - pad, top + rootH), 12 * d, 12 * d, fill(look.ink))
            rootT.at(c, pad + inner, top + inner)
            rootD?.at(c, pad + inner, top + inner + rootT.height + 4 * d)
            var y = startY
            val ln = stroke(look.hint, 1.5f * d)
            var lastMid = top + rootH
            rows.forEach { r ->
                val mid = y + inner + titleP.textSize * 0.62f
                // 树枝：从主干拐进卡片
                val p = Path()
                p.moveTo(trunkX, lastMid)
                p.lineTo(trunkX, mid - 8 * d)
                p.quadTo(trunkX, mid, trunkX + 8 * d, mid)
                p.lineTo(cardX, mid)
                c.drawPath(p, ln)
                lastMid = mid - 8 * d
                card(c, look, RectF(cardX, y, cardX + cardW, y + r.h))
                c.drawCircle(cardX + 0.5f * d, mid, 3 * d, fill(look.accent))
                r.t.at(c, cardX + inner, y + inner)
                var yy = y + inner + r.t.height
                r.det?.let { it.at(c, cardX + inner, yy + 4 * d); yy += it.height + 4 * d }
                r.kids?.let {
                    c.drawLine(cardX + inner, yy + 5 * d, cardX + cardW - inner, yy + 5 * d, stroke(look.line, 1 * d))
                    it.at(c, cardX + inner, yy + 10 * d)
                }
                y += r.h + gap
            }
        }
    }

    /** 方案对比：两个并排，多了上下排；选中的橙框加角标 */
    private fun compare(s: CompareSpec, look: Look, pad: Float, top: Float, w: Float): Plan {
        val d = look.d
        val side = s.options.size == 2 && w / d >= 300
        val gap = 10 * d
        val cw = if (side) (w - pad * 2 - gap) / 2 else w - pad * 2
        val inner = 12 * d
        val nameP = paint(look, 15f, look.ink, true)
        val itemP = paint(look, 13f, look.ink)
        val noteP = paint(look, 12f, look.sub)
        class Col(val o: Opt, val name: StaticLayout, val badge: Chip?, val items: List<Pair<Boolean, StaticLayout>>, val note: StaticLayout?, val h: Float)
        val cols = s.options.map { o ->
            val badge = if (o.picked) chip(look, "选了这个") else null
            val name = text(o.name, nameP, (cw - inner * 2 - (if (badge != null && !side) badge.w + 8 * d else 0f)).toInt())
            val items = o.pros.map { true to text(it, itemP, (cw - inner * 2 - 16 * d).toInt()) } +
                o.cons.map { false to text(it, itemP, (cw - inner * 2 - 16 * d).toInt()) }
            val note = o.note.takeIf { it.isNotBlank() }?.let { text(it, noteP, (cw - inner * 2).toInt()) }
            val h = inner * 2 + name.height + (if (side && badge != null) badge.h + 6 * d else 0f) +
                items.sumOf { it.second.height.toDouble() + 6 * d }.toFloat() + (if (items.isNotEmpty()) 6 * d else 0f) +
                (note?.let { it.height + 8 * d } ?: 0f)
            Col(o, name, badge, items, note, h)
        }
        val rowH = if (side) cols.maxOf { it.h } else 0f
        val total = if (side) rowH else cols.sumOf { it.h.toDouble() }.toFloat() + gap * (cols.size - 1)
        return Plan(top + total) { c ->
            var x = pad
            var y = top
            cols.forEach { col ->
                val h = if (side) rowH else col.h
                val r = RectF(x, y, x + cw, y + h)
                card(c, look, r, border = if (col.o.picked) look.accent else look.line)
                if (col.o.picked) c.drawRoundRect(r, 12 * d, 12 * d, stroke(look.accent, 1.6f * d))
                var yy = y + inner
                if (side && col.badge != null) {
                    drawChip(c, look, col.badge, x + inner, yy)
                    yy += col.badge.h + 6 * d
                } else col.badge?.let { drawChip(c, look, it, x + cw - inner - it.w, yy) }
                col.name.at(c, x + inner, yy)
                yy += col.name.height + 6 * d
                col.items.forEach { (pro, l) ->
                    val p = paint(look, 14f, if (pro) look.good else look.risk, true)
                    c.drawText(if (pro) "+" else "−", x + inner, yy + p.textSize * 0.95f, p)
                    l.at(c, x + inner + 16 * d, yy + 1 * d)
                    yy += l.height + 6 * d
                }
                col.note?.let { it.at(c, x + inner, yy + 2 * d) }
                if (side) x += cw + gap else y += h + gap
            }
        }
    }

    /** 数字对比：每行名字和数，下面一根条，最大的橙色 */
    private fun bars(s: BarsSpec, look: Look, pad: Float, top: Float, w: Float): Plan {
        val d = look.d
        val list = s.bars.sortedByDescending { it.value }
        val maxV = list.maxOf { kotlin.math.abs(it.value) }.takeIf { it > 0 } ?: 1.0
        val labelP = paint(look, 13f, look.ink)
        val valP = paint(look, 13f, look.ink, true)
        val noteP = paint(look, 11.5f, look.sub)
        val bw = w - pad * 2
        class Row(val b: Bar, val label: StaticLayout, val v: String, val note: StaticLayout?, val h: Float)
        val rows = list.map { b ->
            val v = num(b.value) + if (s.unit.isNotBlank()) " ${s.unit}" else ""
            val label = text(b.label, labelP, (bw - valP.measureText(v) - 12 * d).toInt())
            val note = b.note.takeIf { it.isNotBlank() }?.let { text(it, noteP, bw.toInt()) }
            Row(b, label, v, note, label.height + 6 * d + 8 * d + (note?.let { it.height + 4 * d } ?: 0f))
        }
        val gap = 12 * d
        val total = rows.sumOf { it.h.toDouble() }.toFloat() + gap * (rows.size - 1)
        return Plan(top + total) { c ->
            var y = top
            rows.forEachIndexed { i, r ->
                r.label.at(c, pad, y)
                c.drawText(r.v, w - pad - valP.measureText(r.v), y + valP.textSize, valP)
                val by = y + r.label.height + 6 * d
                c.drawRoundRect(RectF(pad, by, pad + bw, by + 8 * d), 4 * d, 4 * d, fill(look.line))
                val len = max(8 * d, (bw * (kotlin.math.abs(r.b.value) / maxV)).toFloat())
                c.drawRoundRect(RectF(pad, by, pad + len, by + 8 * d), 4 * d, 4 * d, fill(if (i == 0) look.accent else look.hint))
                r.note?.at(c, pad, by + 8 * d + 4 * d)
                y += r.h + gap
            }
        }
    }
}
