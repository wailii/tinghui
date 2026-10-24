package com.xiaodeng.tinghui.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.FeishuSync
import com.xiaodeng.tinghui.Outbound
import com.xiaodeng.tinghui.Prefs
import com.xiaodeng.tinghui.Status
import com.xiaodeng.tinghui.Store
import com.xiaodeng.tinghui.Tip
import com.xiaodeng.tinghui.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 我的：飞书里的入口、CodeX 给你发什么、自动做什么、你说话的样子、更多设置 */
@Composable
fun MeScreen(bottomPad: Dp, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val feishu by Prefs.feishu.collectAsState()
    val space by Prefs.space.collectAsState()
    val push by Prefs.push.collectAsState()
    val name by Prefs.feishuName.collectAsState()
    val style by Prefs.voiceStyle.collectAsState()
    val recs by Store.recs.collectAsState()
    var styleSheet by remember { mutableStateOf(false) }
    var feishuSetup by remember { mutableStateOf(false) }
    var backfill by remember { mutableStateOf<String?>(null) }
    val me = Voice.me()?.name ?: name.ifBlank { "我" }
    val unsynced = recs.filter { it.status == Status.Done && it.summary.isNotBlank() && it.fs.syncedAt == 0L }

    fun open(url: String) {
        if (url.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        else Tip.show("还没存过东西到飞书")
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = bottomPad + 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Palette.Accent), contentAlignment = Alignment.Center) {
                    T(me.take(1), 20, Color.White, FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(me, 22, Palette.Ink, FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (feishu.ready) Palette.Good else Palette.Hint))
                        T(
                            if (feishu.ready) " ${feishu.botName.ifBlank { "飞书" }} 已接上" else " 没接飞书", 13, Palette.Sub,
                            modifier = Modifier.pressable(enabled = !feishu.ready) { feishuSetup = true },
                        )
                    }
                }
            }

            Label("飞书存档")
            Group0 {
                Link("会议总表", "多维表格：会议、待办、结论") { open(space.baseUrl) }
                Div()
                Link("纪要文件夹", "纪要、转写稿、录音") { open(space.folderUrl) }
                if (feishu.ready && unsynced.isNotEmpty()) {
                    Div()
                    Link("${unsynced.size} 场会还没同步到飞书", backfill ?: "同步") {
                        if (backfill != null) return@Link
                        backfill = "开始"
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                unsynced.forEachIndexed { k, r ->
                                    backfill = "${k + 1}/${unsynced.size}"
                                    runCatching { FeishuSync.sync(r.id) }
                                }
                            }
                            backfill = null
                            Tip.show("存好了")
                        }
                    }
                }
            }

            Label("CodeX 私聊通知")
            Group0 {
                Switch("会前提醒", "开会前 15 分钟，发上次同一会议没完成的待办和未定事项", push.before) { Prefs.setPush(push.copy(before = it)) }
                if (push.before) T(
                    "试一下", 14, Palette.Ink, FontWeight.SemiBold,
                    Modifier.padding(start = 6.dp, bottom = 6.dp).pressable {
                        val t = com.xiaodeng.tinghui.Cal.test(ctx)
                        Tip.show(if (t == null) "还没有对上日程的会，录过一场日历里的会再试" else "10 秒后按「$t」提醒一次")
                    }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Div()
                Switch("纪要生成通知", "纪要生成后发摘要和链接", push.after) { Prefs.setPush(push.copy(after = it)) }
            }

            Label("自动同步")
            Group0 {
                Switch("同步到飞书", "纪要、转写稿、录音、会议总表", push.autoSync) { Prefs.setPush(push.copy(autoSync = it)) }
                Div()
                Switch("我的待办自动建飞书任务", null, push.autoTasks) { Prefs.setPush(push.copy(autoTasks = it)) }
            }

            Group0 {
                Link("代写语气", if (style.habits.isBlank()) "未生成" else "从 ${style.count} 句会上发言总结") { styleSheet = true }
                Div()
                Link("更多设置", "日历、声纹、模型", onSettings)
            }
        }
        StyleSheet(styleSheet) { styleSheet = false }
        FeishuSheet(feishuSetup, onDismiss = { feishuSetup = false }) { feishuSetup = false }
    }
}

@Composable
private fun Label(text: String) = T(text, 13, Palette.Sub, FontWeight.SemiBold, Modifier.padding(start = 6.dp, top = 6.dp))

@Composable
private fun Group0(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Card)) { content() }
}

@Composable
private fun Div() = Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Palette.Faint))

@Composable
private fun Link(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(title, 15, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
        T(sub, 13, Palette.Sub, maxLines = 1)
    }
}

@Composable
private fun Switch(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, 15, Palette.Ink, FontWeight.Medium)
            if (sub != null) T(sub, 12, Palette.Sub, modifier = Modifier.padding(top = 1.dp))
        }
        Toggle(on, onChange)
    }
}

/** 你说话的样子：总结出来的习惯和例句；往外发的字照这个写 */
@Composable
private fun StyleSheet(visible: Boolean, onDismiss: () -> Unit) {
    val style by Prefs.voiceStyle.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember(visible) { mutableStateOf(false) }
    var msg by remember(visible) { mutableStateOf<String?>(null) }
    Sheet(visible, onDismiss) {
        T("代写语气", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        T("群消息和催办按这个写", 14, Palette.Sub, modifier = Modifier.padding(top = 4.dp))
        if (style.habits.isBlank()) T("你在会上的发言够 30 句就能生成", 14, Palette.Sub, modifier = Modifier.padding(top = 8.dp))
        else {
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Card).padding(16.dp)) {
                T(style.habits, 14, Palette.Ink, lineHeight = 22)
            }
            if (style.examples.isNotEmpty()) {
                T("会上原话", 13, Palette.Sub, FontWeight.SemiBold, Modifier.padding(top = 14.dp, start = 4.dp))
                Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    style.examples.take(6).forEach { T("「$it」", 14, Palette.Ink, lineHeight = 21) }
                }
            }
        }
        msg?.let { T(it, 13, Palette.AccentText, modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
        Pill(if (busy) "正在生成" else "重新生成", Modifier.padding(top = 16.dp).fillMaxWidth(), enabled = !busy) {
            busy = true
            msg = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Outbound.rebuildStyle() } }
                busy = false
                msg = when {
                    r.isFailure -> r.exceptionOrNull()?.message
                    r.getOrNull() == null -> "你在会上的发言还不到 30 句"
                    else -> null
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
