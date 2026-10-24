package com.xiaodeng.tinghui.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 按下去轻轻缩一下，松手弹回，带一下震动 */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    scaleTo: Float = 0.96f,
    haptic: Int = HapticFeedbackConstants.KEYBOARD_TAP,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) scaleTo else 1f,
        spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "press",
    )
    val view = LocalView.current
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = src,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick?.let { f ->
                {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    f()
                }
            },
        ) {
            view.performHapticFeedback(haptic)
            onClick()
        }
}

@Composable
fun T(
    text: String,
    size: Int,
    color: Color = Palette.Ink,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    lineHeight: Int? = null,
    maxLines: Int = Int.MAX_VALUE,
    tabular: Boolean = false,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontSize = size.sp,
            color = color,
            fontWeight = weight,
            textAlign = align ?: TextAlign.Unspecified,
            lineHeight = lineHeight?.sp ?: (size * 1.35f).sp,
            fontFeatureSettings = if (tabular) "tnum" else null,
        ),
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Palette.Card)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    T(text, 13, Palette.Sub, FontWeight.Medium, modifier)
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    textSize: Int = 17,
    haptic: Int = HapticFeedbackConstants.KEYBOARD_TAP,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        when {
            !enabled -> Palette.Faint
            color != null -> color
            dark -> Palette.Ink
            else -> Palette.Card
        },
        tween(220), label = "pillBg",
    )
    val fg by animateColorAsState(
        when {
            !enabled -> Palette.Sub
            color != null -> Color.White
            dark -> Color.White
            else -> Palette.Ink
        },
        tween(220), label = "pillFg",
    )
    Box(
        modifier
            .height(height)
            .pressable(enabled = enabled, haptic = haptic, onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (!dark && color == null && enabled) Modifier.border(1.dp, Palette.Line, RoundedCornerShape(50)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        T(text, textSize, fg, FontWeight.SemiBold, Modifier.padding(horizontal = 18.dp), maxLines = 1)
    }
}

/** 分段选择，白色滑块带弹簧滑过去 */
@Composable
fun SegmentPicker(options: List<Triple<Int, String, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val idx = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val view = LocalView.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Faint)
            .padding(4.dp)
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * idx, spring(dampingRatio = 0.7f, stiffness = 480f), label = "seg")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .fillMaxHeight()
                .shadow(3.dp, RoundedCornerShape(14.dp), ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Card)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (v, num, unit) ->
                val c by animateColorAsState(if (i == idx) Palette.Ink else Palette.Sub, tween(180), label = "segText")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, null) {
                            view.performHapticFeedback(
                                if (v != selected) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.KEYBOARD_TAP
                            )
                            onSelect(v)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        val size = if (unit.isEmpty() && num.length >= 3) 14 else 17
                        T(num, size, c, if (i == idx) FontWeight.Bold else FontWeight.Medium, tabular = true, maxLines = 1)
                        if (unit.isNotEmpty()) {
                            T(unit, 11, c, FontWeight.Medium, Modifier.padding(start = 1.dp, bottom = 2.dp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

