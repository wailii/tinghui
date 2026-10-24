package com.xiaodeng.tinghui.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.Live
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.RecService
import com.xiaodeng.tinghui.Store
import com.xiaodeng.tinghui.Tip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.xiaodeng.tinghui.clock
import kotlinx.coroutines.delay
import kotlin.math.pow

/** 开会时看的那一页：大计时、实时音量、标记、暂停、结束 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordScreen(onCollapse: () -> Unit) {
    val ctx = LocalContext.current
    val st by Live.state.collectAsState()
    val recs by Store.recs.collectAsState()
    val rec = recs.firstOrNull { it.id == st.recId }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(st.paused, st.recId) {
        while (st.recording && !st.paused) {
            now = SystemClock.elapsedRealtime()
            delay(100)
        }
    }
    var renaming by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(R.drawable.ic_down, onClick = onCollapse)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Card)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulseDot(st.paused || st.silenced, 8)
                    Spacer(Modifier.width(7.dp))
                    T(
                        when {
                            st.paused -> "已暂停"
                            st.silenced -> "麦克风被占用"
                            else -> "正在录音"
                        },
                        14, if (st.silenced && !st.paused) Palette.Accent else Palette.Ink, FontWeight.SemiBold,
                    )
                }
            }

            Row(
                Modifier.padding(top = 30.dp).pressable(scaleTo = 0.98f) { renaming = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(rec?.title ?: "", 22, Palette.Ink, FontWeight.Bold, Modifier.weight(1f, fill = false), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Ico(R.drawable.ic_edit, 18.dp, Palette.Hint)
            }

            Spacer(Modifier.weight(1f))
            T(
                clock(st.elapsed(now)), 76, if (st.paused) Palette.Sub else Palette.Ink, FontWeight.Bold,
                Modifier.fillMaxWidth(), align = TextAlign.Center, tabular = true,
            )
            Wave(st.paused, Modifier.fillMaxWidth().height(96.dp).padding(top = 18.dp))

            // 标记过的时间点
            val marks = rec?.marks.orEmpty().filterNot { it.gap }
            Box(Modifier.fillMaxWidth().height(110.dp).padding(top = 22.dp), contentAlignment = Alignment.TopCenter) {
                if (marks.isNotEmpty()) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        marks.takeLast(8).forEach { m ->
                            key(m.atMs) {
                                val vis = remember { MutableTransitionState(false) }.apply { targetState = true }
                                AnimatedVisibility(vis, enter = fadeIn() + scaleIn(spring(0.5f, 500f), 0.6f)) {
                                    Row(
                                        Modifier
                                            .pointerInput(m) {
                                                // 长按删掉点错的标记
                                                detectTapGestures(onLongPress = {
                                                    st.recId?.let { id -> Store.update(id) { r -> r.copy(marks = r.marks - m) } }
                                                    Live.state.value = Live.state.value.let { it.copy(marks = (it.marks - 1).coerceAtLeast(0)) }
                                                    Tip.show("删掉了 ${clock(m.atMs)} 的标记")
                                                })
                                            }
                                            .clip(RoundedCornerShape(50))
                                            .background(Palette.AccentSoft)
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Ico(R.drawable.ic_star, 14.dp, Palette.Accent)
                                        T(" ${clock(m.atMs)}", 13, Palette.AccentText, FontWeight.SemiBold, tabular = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                SideButton(
                    if (st.paused) R.drawable.ic_mic else R.drawable.ic_pause,
                    if (st.paused) "继续" else "暂停",
                ) { RecService.send(ctx, if (st.paused) RecService.RESUME else RecService.PAUSE) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(88.dp)
                            .pressable(scaleTo = 0.9f, haptic = HapticFeedbackConstants.LONG_PRESS) {
                                RecService.send(ctx, RecService.STOP)
                            }
                            .shadow(14.dp, CircleShape, ambientColor = Color(0x33000000), spotColor = Color(0x44000000))
                            .clip(CircleShape)
                            .background(Palette.Island),
                        contentAlignment = Alignment.Center,
                    ) { Ico(R.drawable.ic_stop, 34.dp, Palette.Rec) }
                    T("结束", 13, Palette.Ink, FontWeight.SemiBold, Modifier.padding(top = 8.dp))
                }
                SideButton(R.drawable.ic_star, "标记", tint = Palette.Accent) { RecService.send(ctx, RecService.MARK) }
            }
            Spacer(Modifier.height(16.dp))
        }
        val r = rec
        if (r != null) RenameSheet(renaming, r) { renaming = false }
    }
}

@Composable
private fun SideButton(icon: Int, label: String, tint: Color = Palette.Ink, onClick: () -> Unit) {
    Column(Modifier.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .pressable(scaleTo = 0.9f, onClick = onClick)
                .clip(CircleShape)
                .background(Palette.Card)
                .border(1.dp, Palette.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Ico(icon, 26.dp, tint) }
        T(label, 13, Palette.Sub, FontWeight.Medium, Modifier.padding(top = 8.dp))
    }
}

/** 实时音量：从右往左滚，越新越黑 */
@Composable
private fun Wave(paused: Boolean, modifier: Modifier) {
    val levels by Live.levels.collectAsState()
    Canvas(modifier) {
        val n = 48
        val gap = 4.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val k = levels.size - n + i
            val lv = if (k >= 0) levels[k] else 0f
            val v = lv.coerceIn(0f, 1f).pow(2.2f)
            val bh = (size.height * v).coerceAtLeast(bw)
            val fresh = 0.25f + 0.75f * (i + 1) / n
            drawRoundRect(
                Palette.Ink.copy(alpha = if (paused) 0.15f else fresh),
                topLeft = Offset(i * (bw + gap), (size.height - bh) / 2),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bw / 2, bw / 2),
            )
        }
    }
}
