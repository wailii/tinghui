package com.xiaodeng.tinghui.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import com.xiaodeng.tinghui.Chart
import com.xiaodeng.tinghui.Flow
import com.xiaodeng.tinghui.Md

// 连着的几个时间 [02:09][02:43] 只显示第一个，满屏橙色数字太乱
private val INLINE = Regex("""\*\*(.+?)\*\*|[（(]\[(\d{1,2}:\d{2}(?::\d{2})?)](?:\s*\[\d{1,2}:\d{2}(?::\d{2})?])*[）)]|\[(\d{1,2}:\d{2}(?::\d{2})?)](?:\s*\[\d{1,2}:\d{2}(?::\d{2})?])*""")

fun parseClock(t: String): Long {
    val p = t.split(":").map { it.toLongOrNull() ?: 0 }
    return (if (p.size == 3) p[0] * 3600 + p[1] * 60 + p[2] else p[0] * 60 + p.getOrElse(1) { 0 }) * 1000
}

/** 一行字：**加粗** 照加粗，[12:35] 变成橙色时间，能跳的点了跳过去播 */
private fun inline(s: String, onSeek: ((Long) -> Unit)?): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(i, m.range.first))
        if (m.groups[1] != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1]) }
        } else {
            val t = m.groups[2]?.value ?: m.groupValues[3]
            val ms = parseClock(t)
            val style = SpanStyle(color = Palette.Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            if (onSeek == null) withStyle(style) { append(" $t") }
            else withLink(LinkAnnotation.Clickable("t$ms", TextLinkStyles(style)) { onSeek(ms) }) { append(" $t") }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

private val Body = TextStyle(fontSize = 15.sp, color = Palette.Ink, lineHeight = 25.sp)

/** 把模型写的 Markdown 排出来：标题、列表、段落、表格、流程图 */
@Composable
fun MinutesView(md: String, onSeek: ((Long) -> Unit)?, modifier: Modifier = Modifier) {
    val blocks = remember(md) { Md.parse(md) }
    Column(modifier) {
        blocks.forEachIndexed { i, b ->
            when (b) {
                is Md.Head -> if (b.level <= 2) T(
                    b.text, 17, Palette.Ink, FontWeight.Bold,
                    Modifier.padding(top = if (i == 0) 0.dp else 20.dp, bottom = 6.dp),
                ) else T(b.text, 15, Palette.Ink, FontWeight.SemiBold, Modifier.padding(top = 12.dp, bottom = 4.dp))
                is Md.Item -> Row(
                    Modifier.fillMaxWidth().padding(start = (b.depth * 16).dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier.padding(top = 10.dp).size(5.dp).clip(CircleShape)
                            .background(if (b.depth == 0) Palette.Sub else Palette.Hint)
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicText(inline(b.text, onSeek), Modifier.weight(1f), style = Body)
                }
                is Md.Quote -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.Line))
                    Spacer(Modifier.width(10.dp))
                    BasicText(inline(b.text, onSeek), Modifier.weight(1f), style = Body.copy(color = Palette.Sub))
                }
                is Md.Para -> BasicText(inline(b.text, onSeek), Modifier.padding(vertical = 3.dp), style = Body)
                is Md.Table -> TableView(b, onSeek)
                is Md.Code -> when (b.lang) {
                    "chart" -> ChartView(b.text, mermaid = false)
                    "json" -> ChartView(b.text, mermaid = false, raw = true)
                    "mermaid" -> ChartView(b.text, mermaid = true, raw = true)
                    else -> CodeView(b.text)
                }
            }
        }
    }
}

/**
 * 表格：按每列字的实际宽度分，短的列（负责人、截止）不折行，长的列分剩下的；
 * 列多到放不下就左右划
 */
@Composable
private fun TableView(t: Md.Table, onSeek: ((Long) -> Unit)?) {
    val cols = t.head.size
    val cell = TextStyle(fontSize = 14.sp, color = Palette.Ink, lineHeight = 21.sp)
    val headStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Bg)
    ) {
        val gap = with(density) { 10.dp.toPx() }
        val avail = constraints.maxWidth - with(density) { 24.dp.toPx() } - gap * (cols - 1)
        val natural = remember(t) {
            (0 until cols).map { c ->
                val h = measurer.measure(Md.plain(t.head[c]), headStyle).size.width
                val body = t.rows.maxOfOrNull { r -> measurer.measure(Md.plain(r[c]).replace(Regex("""\[(\d{1,2}:\d{2})].*"""), " $1"), cell).size.width } ?: 0
                max(h, body).toFloat() + 2
            }
        }
        val minFlex = with(density) { 90.dp.toPx() }
        val widths: List<Float>
        val scroll: Boolean
        if (natural.sum() <= avail) {
            // 都放得下：多出来的给最长的那列
            val extra = avail - natural.sum()
            val longest = natural.indices.maxBy { natural[it] }
            widths = natural.mapIndexed { i, w -> if (i == longest) w + extra else w }
            scroll = false
        } else {
            val short = natural.indices.filter { natural[it] <= avail * 0.3f }.toSet()
            val left = avail - short.sumOf { natural[it].toDouble() }.toFloat()
            val flex = natural.indices.filter { it !in short }
            val flexSum = flex.sumOf { natural[it].toDouble() }.toFloat()
            if (flex.isNotEmpty() && flex.all { left * natural[it] / flexSum >= minFlex }) {
                widths = natural.mapIndexed { i, w -> if (i in short) w else left * w / flexSum }
                scroll = false
            } else {
                val cap = with(density) { 240.dp.toPx() }
                widths = natural.map { it.coerceIn(minFlex * 0.8f, cap) }
                scroll = true
            }
        }
        Box(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                @Composable
                fun line(cells: List<String>, head: Boolean) {
                    // 行与行之间一条细线
                    Row(
                        (if (head) Modifier else Modifier.drawBehind {
                            drawLine(Palette.Line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                        }).padding(vertical = 7.dp)
                    ) {
                        cells.forEachIndexed { c, s ->
                            Box(Modifier.width(with(density) { widths[c].toDp() }).padding(end = if (c < cols - 1) 0.dp else 0.dp)) {
                                if (head) T(Md.plain(s), 12, Palette.Sub, FontWeight.SemiBold)
                                else BasicText(inline(s, onSeek), style = cell)
                            }
                            if (c < cols - 1) Spacer(Modifier.width(10.dp))
                        }
                    }
                }
                line(t.head, true)
                t.rows.forEach { line(it, false) }
            }
        }
    }
}

/** 图：```chart 是模型填的 JSON，```mermaid 是老写法；都画不出来就不显示 */
@Composable
private fun ChartView(code: String, mermaid: Boolean, raw: Boolean = false) {
    BoxWithConstraints(Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))) {
        val d = LocalDensity.current.density
        val w = constraints.maxWidth
        val bmp = remember(code, w) {
            runCatching {
                val look = Chart.Look(
                    d, bg = Palette.Bg.toArgb(), card = Palette.Card.toArgb(), line = Palette.Line.toArgb(),
                    ink = Palette.Ink.toArgb(), sub = Palette.Sub.toArgb(), hint = Palette.Hint.toArgb(),
                    accent = Palette.Accent.toArgb(), accentSoft = Palette.AccentSoft.toArgb(), accentText = Palette.AccentText.toArgb(),
                    good = Palette.Good.toArgb(),
                )
                if (mermaid) Flow.render(code, w, look) else Chart.parse(code)?.let { Chart.render(it, w, look) }
            }.getOrNull()
        }
        when {
            // 画不出来：mermaid 和普通 json 按原文显示，模型写坏的 chart 不显示
            bmp == null -> if (raw) CodeView(code)
            bmp.width <= w -> Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth())
            // 一层框多，图比屏幕宽：左右划着看
            else -> Box(Modifier.horizontalScroll(rememberScrollState())) {
                Image(bmp.asImageBitmap(), null, Modifier.size((bmp.width / d).dp, (bmp.height / d).dp))
            }
        }
    }
}

@Composable
private fun CodeView(code: String) {
    BasicText(
        code,
        Modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Bg)
            .padding(12.dp),
        style = TextStyle(fontSize = 13.sp, color = Palette.Ink, lineHeight = 20.sp, fontFamily = FontFamily.Monospace),
    )
}
