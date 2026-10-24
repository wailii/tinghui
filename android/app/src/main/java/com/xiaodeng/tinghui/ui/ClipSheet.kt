package com.xiaodeng.tinghui.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.Feishu
import com.xiaodeng.tinghui.FeishuPush
import com.xiaodeng.tinghui.Para
import com.xiaodeng.tinghui.Prefs
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.Rec
import com.xiaodeng.tinghui.Tip
import com.xiaodeng.tinghui.clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 长按转写稿里的一段：复制，或者把这段原话的录音发到飞书群 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClipSheet(p: Para?, name: String, rec: Rec, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val feishu by Prefs.feishu.collectAsState()
    val lastGroup by Prefs.lastGroup.collectAsState()
    var groups by remember(p) { mutableStateOf<List<Feishu.Chat>?>(null) }
    var group by remember(p) { mutableStateOf<String?>(null) }
    var busy by remember(p) { mutableStateOf(false) }
    var err by remember(p) { mutableStateOf<String?>(null) }
    LaunchedEffect(p, feishu.ready) {
        if (p == null || !feishu.ready) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { Feishu.chats() } }.onSuccess { l ->
            groups = l.sortedByDescending { it.id == lastGroup }
            group = l.firstOrNull { it.id == lastGroup }?.id ?: l.firstOrNull()?.id
        }.onFailure { err = it.message }
    }
    val secs = if (p == null) 0 else ((p.end - p.begin + 1_000) / 1000).coerceIn(5, 60)
    Sheet(p != null, onDismiss) {
        if (p == null) return@Sheet
        T("${clock(p.begin)}  $name", 13, Palette.Sub, FontWeight.SemiBold, Modifier.padding(top = 18.dp, start = 4.dp))
        T(p.text, 15, Palette.Ink, lineHeight = 22, maxLines = 4, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        Pill("复制这段", Modifier.padding(top = 14.dp).fillMaxWidth(), dark = false) {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("段落", "[${clock(p.begin)}] $name：${p.text}"))
            Tip.show("这段已复制")
            onDismiss()
        }
        if (feishu.ready) {
            Row(Modifier.padding(top = 18.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Ico(R.drawable.ic_feishu, 18.dp, Color.Unspecified)
                Spacer(Modifier.width(6.dp))
                T("把这段原话的录音发到群（$secs 秒）", 13, Palette.Sub, FontWeight.SemiBold)
            }
            when {
                groups == null && err == null -> Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BusyDots()
                    T("找 CodeX 在的群", 14, Palette.Sub)
                }
                groups.isNullOrEmpty() -> T(err ?: "CodeX 还没进群", 13, Palette.Sub, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                else -> FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups!!.forEach { g ->
                        val on = g.id == group
                        T(
                            g.name, 13, if (on) Color.White else Palette.Ink, if (on) FontWeight.Bold else FontWeight.Medium,
                            Modifier.pressable(enabled = !busy) { group = g.id }.clip(RoundedCornerShape(50))
                                .background(if (on) Palette.Ink else Palette.Card)
                                .border(1.dp, if (on) Palette.Ink else Palette.Line, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (err != null && !groups.isNullOrEmpty()) T(err ?: "", 13, Palette.AccentText, lineHeight = 19, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            val g = groups?.firstOrNull { it.id == group }
            if (g != null) Pill(if (busy) "正在切录音、发送" else "发到「${g.name}」", Modifier.padding(top = 14.dp).fillMaxWidth(), enabled = !busy) {
                busy = true
                err = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { FeishuPush.sendClip(rec, g.id, p.begin, secs * 1000L) } }
                    busy = false
                    r.onSuccess {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        Tip.show("已发到「${g.name}」")
                        onDismiss()
                    }.onFailure { err = it.message ?: "没发成" }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
