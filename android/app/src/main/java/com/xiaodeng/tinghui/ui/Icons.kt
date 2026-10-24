package com.xiaodeng.tinghui.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Ico(@DrawableRes id: Int, size: Dp = 22.dp, tint: Color = Palette.Ink, modifier: Modifier = Modifier) {
    // tint 给 Unspecified：用图标自己的颜色（飞书 Logo 这种）
    Image(painterResource(id), null, modifier.size(size), colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint))
}

/** 圆形图标按钮：返回、更多、设置这类 */
@Composable
fun RoundIcon(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    icon: Dp = 22.dp,
    bg: Color = Palette.Card,
    tint: Color = Palette.Ink,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .pressable(enabled = enabled, scaleTo = 0.9f, onClick = onClick)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) { Ico(id, icon, if (enabled) tint else Palette.Hint) }
}
