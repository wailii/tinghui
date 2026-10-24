package com.xiaodeng.tinghui.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.xiaodeng.tinghui.BuildConfig
import com.xiaodeng.tinghui.Feishu
import com.xiaodeng.tinghui.FeishuPush
import com.xiaodeng.tinghui.Outbound
import com.xiaodeng.tinghui.Prefs
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.Rec
import com.xiaodeng.tinghui.Store
import com.xiaodeng.tinghui.Tip
import com.xiaodeng.tinghui.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 飞书文档图标的蓝 */
private val DocBlue = Color(0xFF3370FF)

/** 发什么：标签、交给模型的写法 */
private val KINDS = listOf("结论和待办" to "", "只发待办" to "todos", "只发结论" to "decided")

/**
 * 发送到飞书群：选群、选发什么，点「生成」才写；写好按群里看到的样子预览，点文字能改。
 * 默认复制好、打开那个群，你自己发；也可以让 CodeX 直接发
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrapSheet(visible: Boolean, rec: Rec, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val feishu by Prefs.feishu.collectAsState()
    val lastGroup by Prefs.lastGroup.collectAsState()
    val myName by Prefs.feishuName.collectAsState()
    var groups by remember(visible) { mutableStateOf<List<Feishu.Chat>?>(null) }
    var groupErr by remember(visible) { mutableStateOf<String?>(null) }
    var group by remember(visible) { mutableStateOf<String?>(null) }
    var kind by remember(visible) { mutableStateOf("") }
    var text by remember(visible, rec.id) { mutableStateOf("") }
    // 正在写：写的是什么（显示给你看）；null 没在写
    var writing by remember(visible) { mutableStateOf<String?>(null) }
    var asking by remember(visible) { mutableStateOf(false) }
    var ask by remember(visible) { mutableStateOf("") }
    var busy by remember(visible) { mutableStateOf<String?>(null) }
    var err by remember(visible) { mutableStateOf<String?>(null) }
    var feishuSetup by remember { mutableStateOf(false) }
    val me = Voice.me()?.name ?: myName.substringAfterLast('·').ifBlank { BuildConfig.FEISHU_USER_NAME.ifBlank { "我" } }

    fun write(what: String, job: () -> String) {
        if (writing != null || rec.summary.isBlank()) return
        writing = what
        err = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { job() } }
            writing = null
            r.onSuccess { text = it }.onFailure { err = it.message }
        }
    }

    fun generate(k: String = kind) {
        val label = KINDS.firstOrNull { it.second == k }?.first ?: "结论和待办"
        write("正在生成「$label」") { Outbound.groupMessage(rec, k) }
    }

    LaunchedEffect(visible, feishu.ready) {
        if (!visible || !feishu.ready) return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { Feishu.chats() } }
        r.onSuccess { l ->
            // 上次发过的群排前面
            groups = l.sortedByDescending { it.id == lastGroup }
            group = l.firstOrNull { it.id == lastGroup }?.id ?: l.firstOrNull()?.id
        }.onFailure { groupErr = it.message }
    }

    fun open(url: String) {
        if (url.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** viaBot：让 CodeX 直接发；不然复制好、打开那个群，你自己发 */
    fun go(viaBot: Boolean) {
        val g = groups?.firstOrNull { it.id == group } ?: return
        if (busy != null || text.isBlank()) return
        busy = if (viaBot) "发到「${g.name}」" else "给群开纪要权限"
        err = null
        scope.launch {
            var link = ""
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val cur = Store.get(rec.id) ?: rec
                    if (viaBot) FeishuPush.toGroup(cur, g.id, text.trim())
                    else link = FeishuPush.prepareGroup(cur, g.id).fs.minutesUrl
                }
            }
            busy = null
            r.onSuccess {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                if (viaBot) Tip.show("已发到「${g.name}」")
                else {
                    val clip = text.trim() + if (link.isNotBlank()) "\n纪要：$link" else ""
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("会后同步", clip))
                    Tip.show("已复制，粘贴发出去就行")
                    open(FeishuPush.chatLink(g.id))
                }
                onDismiss()
            }.onFailure { err = it.message ?: "没发成" }
        }
    }

    Sheet(visible, onDismiss) {
        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Ico(R.drawable.ic_feishu, 26.dp, Color.Unspecified)
            Spacer(Modifier.width(8.dp))
            T("发送到飞书群", 22, weight = FontWeight.Bold)
        }
        if (!feishu.ready) {
            T("先接上飞书", 15, Palette.Sub, modifier = Modifier.padding(top = 8.dp))
            Pill("接飞书", Modifier.padding(top = 14.dp).fillMaxWidth()) { feishuSetup = true }
            Spacer(Modifier.height(8.dp))
            return@Sheet
        }

        // 发给哪个群：CodeX 在的群全列出来
        T("发给哪个群", 13, Palette.Sub, FontWeight.SemiBold, Modifier.padding(top = 14.dp, start = 4.dp))
        when {
            groups == null && groupErr == null -> Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BusyDots()
                T("找 CodeX 在的群", 14, Palette.Sub)
            }
            groupErr != null -> T(groupErr ?: "", 13, Palette.AccentText, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            groups.isNullOrEmpty() -> T("CodeX 还没进群。在群设置里把它加成机器人", 13, Palette.Sub, lineHeight = 19, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            else -> FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups!!.forEach { g -> Chip(g.name, enabled = busy == null, on = g.id == group) { group = g.id } }
            }
        }

        // 发什么
        T("发什么", 13, Palette.Sub, FontWeight.SemiBold, Modifier.padding(top = 14.dp, start = 4.dp))
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KINDS.forEach { (label, k) ->
                Chip(label, enabled = writing == null, on = k == kind) {
                    if (k == kind) return@Chip
                    kind = k
                    // 已经写过一版：换了就按新的重写
                    if (text.isNotBlank()) generate(k)
                }
            }
        }

        if (text.isBlank() && writing == null) {
            err?.let { T(it, 13, Palette.AccentText, lineHeight = 19, modifier = Modifier.padding(top = 12.dp, start = 4.dp)) }
            Pill("生成", Modifier.padding(top = 18.dp).fillMaxWidth(), enabled = rec.summary.isNotBlank()) { generate() }
            if (rec.summary.isBlank()) T("这场会还没有纪要", 13, Palette.Sub, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            Spacer(Modifier.height(4.dp))
            return@Sheet
        }

        // 群里会看到的样子
        T("群里会看到（点文字能改）", 13, Palette.Sub, FontWeight.SemiBold, Modifier.padding(top = 16.dp, start = 4.dp))
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Faint).padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.Accent), contentAlignment = Alignment.Center) {
                T(me.take(1), 15, Color.White, FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(me, 12, Palette.Sub)
                if (writing != null) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp)).background(Palette.Card).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BusyDots()
                    T(writing ?: "", 14, Palette.Sub)
                } else Box(Modifier.clip(RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp))) {
                    Field(text, { text = it }, "要发的话", size = 14, singleLine = false, minHeight = 60)
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Card).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(R.drawable.ic_doc, 20.dp, DocBlue)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        T("${rec.title} · 纪要", 13, Palette.Ink, FontWeight.Bold, maxLines = 1)
                        T("群成员可查看", 11, Palette.Sub)
                    }
                }
            }
        }

        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("重新生成", enabled = writing == null) { generate() }
            Chip("我说要求…", enabled = writing == null, on = asking) { asking = !asking }
        }
        if (asking) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(ask, { ask = it }, "比如：待办都加上截止时间", Modifier.weight(1f), size = 14, onDone = {
                if (ask.isNotBlank() && text.isNotBlank()) write("正在按要求改") { Outbound.reviseGroupMessage(rec, text, ask.trim()) }
            })
            Spacer(Modifier.width(8.dp))
            Pill("改", height = 46.dp, textSize = 14, enabled = writing == null && ask.isNotBlank() && text.isNotBlank()) {
                write("正在按要求改") { Outbound.reviseGroupMessage(rec, text, ask.trim()) }
            }
        }

        err?.let { T(it, 13, Palette.AccentText, lineHeight = 19, modifier = Modifier.padding(top = 12.dp, start = 4.dp)) }
        val g = groups?.firstOrNull { it.id == group }
        Pill(
            busy ?: if (g != null) "复制，去「${g.name}」发" else "选个群",
            Modifier.padding(top = 18.dp).fillMaxWidth(),
            enabled = busy == null && writing == null && g != null && text.isNotBlank(),
        ) { go(viaBot = false) }
        if (g != null && busy == null) T(
            "让 ${feishu.botName.ifBlank { "CodeX" }} 直接发", 14, if (writing != null || text.isBlank()) Palette.Hint else Palette.Sub, FontWeight.Medium,
            Modifier.align(Alignment.CenterHorizontally).pressable(enabled = writing == null && text.isNotBlank()) { go(viaBot = true) }.padding(12.dp),
        )
    }
    FeishuSheet(feishuSetup, onDismiss = { feishuSetup = false }) { feishuSetup = false }
}

@Composable
private fun Chip(label: String, enabled: Boolean, on: Boolean = false, onClick: () -> Unit) {
    T(
        label, 13, if (on) Color.White else if (!enabled) Palette.Hint else Palette.Ink, if (on) FontWeight.Bold else FontWeight.Medium,
        Modifier.pressable(enabled = enabled, onClick = onClick).clip(RoundedCornerShape(50))
            .background(if (on) Palette.Ink else Palette.Card)
            .border(1.dp, if (on) Palette.Ink else Palette.Line, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
        maxLines = 1,
    )
}
