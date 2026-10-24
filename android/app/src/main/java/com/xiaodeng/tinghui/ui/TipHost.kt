package com.xiaodeng.tinghui.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.Tip
import kotlinx.coroutines.delay

/** 顶部黑色小提示：从状态栏下面滑出来，两秒多自己收回去；带按钮的多停一会 */
@Composable
fun TipHost() {
    val msg by Tip.msg.collectAsState()
    var shown by remember { mutableStateOf<Tip.Msg?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(msg) {
        val m = msg ?: return@LaunchedEffect
        shown = m
        visible = true
        delay(if (m.action != null) 4000 else 2200)
        visible = false
        Tip.msg.compareAndSet(m, null)
    }
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible,
            enter = slideInVertically(spring(0.75f, 500f)) { -it } + fadeIn(),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
        ) {
            val m = shown
            Row(
                Modifier
                    .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                    .shadow(12.dp, RoundedCornerShape(50), ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Island)
                    .padding(start = 20.dp, end = if (m?.action != null) 6.dp else 20.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(m?.text ?: "", 14, Color.White, FontWeight.SemiBold, Modifier.padding(vertical = 6.dp))
                if (m?.action != null) {
                    Spacer(Modifier.width(12.dp))
                    T(
                        m.action, 14, Palette.Ink, FontWeight.SemiBold,
                        Modifier
                            .pressable {
                                visible = false
                                m.onAction?.invoke()
                            }
                            .clip(RoundedCornerShape(50))
                            .background(Color.White)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
