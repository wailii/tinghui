package com.xiaodeng.tinghui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.Cal
import com.xiaodeng.tinghui.Feishu
import com.xiaodeng.tinghui.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

// ---------- 飞书日历 ----------

/** 接飞书日历：填飞书里生成的 CalDAV 用户名和密码；接上后选提前几分钟提醒 */
@Composable
fun CalSheet(visible: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by Prefs.cal.collectAsState()
    val events by Cal.events.collectAsState()
    var user by remember(visible) { mutableStateOf(cfg.user) }
    var pass by remember(visible) { mutableStateOf(cfg.pass) }
    var checking by remember(visible) { mutableStateOf(false) }
    var result by remember(visible) { mutableStateOf<Pair<Boolean, String>?>(null) }
    // 接上了就收起账号密码，要换再展开
    var editing by remember(visible) { mutableStateOf(!cfg.ready) }
    val scope = rememberCoroutineScope()
    Sheet(visible, onDismiss) {
        T("飞书日历", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        T("开会前提醒，点一下开录", 14, Palette.Sub, modifier = Modifier.padding(top = 4.dp))
        if (editing) {
            T("在飞书日历设置里找「CalDAV」，生成用户名和密码", 13, Palette.Sub, modifier = Modifier.padding(top = 10.dp))
            Field(user, { user = it.trim() }, "CalDAV 用户名", Modifier.padding(top = 10.dp), size = 15)
            Field(pass, { pass = it.trim() }, "CalDAV 密码", Modifier.padding(top = 8.dp), size = 15)
        }
        result?.let { (ok, msg) ->
            T(msg, 13, if (ok) Palette.Good else Palette.Accent, FontWeight.Medium, Modifier.padding(top = 12.dp, start = 4.dp), lineHeight = 19)
        }
        if (editing) {
            Pill(
                if (checking) "正在试…" else "试一下，能用就保存",
                Modifier.padding(top = 16.dp).fillMaxWidth(),
                enabled = !checking && user.isNotBlank() && pass.isNotBlank(),
            ) {
                checking = true
                result = null
                scope.launch {
                    val c = cfg.copy(user = user, pass = pass)
                    val r = withContext(Dispatchers.IO) { runCatching { Cal.test(c) } }
                    checking = false
                    r.onSuccess { l ->
                        Prefs.setCal(c)
                        editing = false
                        withContext(Dispatchers.IO) { Cal.sync(ctx) }
                        result = true to (if (l.isEmpty()) "接上了，接下来一天没有会" else "接上了，接下来一天有 ${l.size} 场会")
                    }.onFailure { result = false to (it.message ?: "接不上") }
                }
            }
        } else {
            // 接上了：接下来的几场会、提前几分钟
            val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
            Column(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Card)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val next = events.filter { it.end > System.currentTimeMillis() }.take(3)
                if (next.isEmpty()) T("接下来一天没有会", 14, Palette.Sub)
                next.forEach { e ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(fmt.format(e.start), 13, Palette.Sub, tabular = true, modifier = Modifier.width(96.dp))
                        T(e.title, 14, Palette.Ink, FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                T("开会前提醒", 16, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
                Toggle(cfg.remind) {
                    Prefs.setCal(cfg.copy(remind = it))
                    Cal.schedule(ctx)
                }
            }
            if (cfg.remind) {
                Box(Modifier.padding(top = 10.dp)) {
                    SegmentPicker(listOf(Triple(1, "1", " 分钟"), Triple(2, "2", " 分钟"), Triple(5, "5", " 分钟")), cfg.lead) {
                        Prefs.setCal(cfg.copy(lead = it))
                        Cal.schedule(ctx)
                    }
                }
            }
            Row(Modifier.padding(top = 14.dp)) {
                T("换一组账号", 14, Palette.Sub, FontWeight.Medium, Modifier.pressable { editing = true }.padding(4.dp))
                Spacer(Modifier.width(18.dp))
                T(
                    "断开", 14, Palette.Sub, FontWeight.Medium,
                    Modifier.pressable {
                        Prefs.setCal(Prefs.Cal("", ""))
                        scope.launch(Dispatchers.IO) { Cal.sync(ctx) }
                        onDismiss()
                    }.padding(4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
