package com.xiaodeng.tinghui.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.xiaodeng.tinghui.Tip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.BuildConfig
import com.xiaodeng.tinghui.Prefs
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.RecTile
import com.xiaodeng.tinghui.Store
import com.xiaodeng.tinghui.Voice

@Composable
fun SettingsScreen(onBack: () -> Unit, onBattery: () -> Unit) {
    val ctx = LocalContext.current
    val hot by Prefs.hotwords.collectAsState()
    val people by Voice.people.collectAsState()
    val key by Prefs.apiKey.collectAsState()
    val feishu by Prefs.feishu.collectAsState()
    val recs by Store.recs.collectAsState()
    var person by remember { mutableStateOf<String?>(null) }
    var editKey by remember { mutableStateOf(false) }
    var editHot by remember { mutableStateOf(false) }
    var editFeishu by remember { mutableStateOf(false) }
    var editCal by remember { mutableStateOf(false) }
    val cal by Prefs.cal.collectAsState()

    var editLlm by remember { mutableStateOf(false) }
    var editFolder by remember { mutableStateOf<String?>(null) }
    var showTpls by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var editTpl by remember { mutableStateOf<String?>(null) }
    val templates by Prefs.templates.collectAsState()
    val defTpl by Prefs.defaultTemplate.collectAsState()
    val llm by Prefs.llm.collectAsState()
    val folders by Prefs.folders.collectAsState()

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(R.drawable.ic_back, onClick = onBack)
                T("设置", 22, Palette.Ink, FontWeight.Bold, Modifier.padding(start = 12.dp))
            }

            Section("转写 · 阿里百炼")
            Group {
                ValueRow("API Key", maskKey(key), accent = key.isBlank()) { editKey = true }
                Divider0()
                ValueRow("热词", "${hot.lines().count { it.isNotBlank() && Prefs.validHotword(it.trim()) }} 个") { editHot = true }
            }

            Section("飞书")
            Group {
                ValueRow(
                    "飞书机器人",
                    if (feishu.ready) feishu.botName.ifBlank { "已接上" } else "未接上",
                    accent = !feishu.ready,
                ) { editFeishu = true }
                Divider0()
                ValueRow(
                    "飞书日历",
                    if (!cal.ready) "未接上" else if (cal.remind) "提前 ${cal.lead} 分钟提醒" else "不提醒",
                ) { editCal = true }

            }

            Section("纪要")
            Group {
                ValueRow("大模型", llm.model.ifBlank { "未配置" }, accent = !llm.ready) { editLlm = true }
                Divider0()
                ValueRow("模板", "${templates.size} 个 · 默认「${Prefs.template(defTpl).name}」") { showTpls = true }
                Divider0()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T("录完自动出纪要", 16, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
                    Toggle(llm.auto) { Prefs.setLlm(llm.copy(auto = it)) }
                }
            }

            Section("分类")
            Group {
                folders.forEachIndexed { i, f ->
                    if (i > 0) Divider0()
                    val n = recs.count { it.folder == f.name }
                    ValueRow(f.name, "$n 场") { editFolder = f.name }
                }
                if (folders.isNotEmpty()) Divider0()
                ValueRow("新建分类", "") { editFolder = NEW_FOLDER }
                if (llm.ready) {
                    Divider0()
                    // 会动所有会议的分类，点两下才开始
                    ValueRow("重新整理全部分类", organizing ?: if (armed) "再点一次开始" else "", accent = organizing != null || armed) {
                        if (organizing != null) return@ValueRow
                        if (!armed) {
                            armed = true
                            scope.launch { kotlinx.coroutines.delay(4000); armed = false }
                            return@ValueRow
                        }
                        armed = false
                        organizing = "整理中"
                        scope.launch {
                            val before = recs.associate { it.id to it.folder }
                            val r = withContext(Dispatchers.IO) {
                                runCatching { com.xiaodeng.tinghui.Llm.organize { organizing = it } }
                            }
                            organizing = null
                            Tip.show(r.fold({ "整理好了，$it 个分类" }, { it.message ?: "没整理成" }))
                            // 分类变了的会，飞书里的文档跟着挪到新分类的文件夹
                            if (r.isSuccess && Prefs.feishu.value.ready) withContext(Dispatchers.IO) {
                                com.xiaodeng.tinghui.Store.recs.value.filter { it.fs.syncedAt > 0 && before[it.id] != it.folder }
                                    .forEach { runCatching { com.xiaodeng.tinghui.FeishuSync.sync(it.id) } }
                            }
                        }
                    }
                }
            }
            Note("新纪要会归到已有分类里最合适的一个，都不合适才新建；手动改过分类的会不会被动")

            Section("声纹库")
            Group {
                if (people.isEmpty()) {
                    T("还没有", 14, Palette.Sub, modifier = Modifier.padding(18.dp))
                } else {
                    people.forEachIndexed { i, p ->
                        if (i > 0) Divider0()
                        val meetings = recs.count { r -> r.speakers.any { it.personId == p.id } }
                        ValueRow(p.name, "$meetings 场") { person = p.id }
                    }
                }
            }
            Note("在一场会里给说话人标上名字，以后的会里能自动认出这个人")

            Section("录音")
            Group {
                ValueRow("加到下拉快捷开关", "") {
                    val sbm = ctx.getSystemService(StatusBarManager::class.java)
                    sbm.requestAddTileService(
                        ComponentName(ctx, RecTile::class.java), "听会录音",
                        Icon.createWithResource(ctx, R.drawable.ic_stat_rec), ctx.mainExecutor,
                    ) { code ->
                        val msg = when (code) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "加好了"
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "已经在下拉开关里了"
                            else -> "没加上，可以在下拉通知栏的编辑里手动加"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }
                Divider0()
                ValueRow("允许后台耗电", "", onClick = onBattery)
                Divider0()
                ValueRow("通知", "") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    )
                }
            }

            T(
                "听会 ${BuildConfig.VERSION_NAME}",
                12, Palette.Hint, modifier = Modifier.padding(start = 6.dp, top = 26.dp, bottom = 30.dp),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Palette.Bg, Palette.Bg, Palette.Bg.copy(alpha = 0f))))
                .windowInsetsTopHeight(WindowInsets.statusBars)
        )

        PersonSheet(person) { person = null }
        KeySheet(editKey) { editKey = false }
        HotwordSheet(editHot) { editHot = false }
        FeishuSheet(editFeishu, onDismiss = { editFeishu = false }, onReady = { editFeishu = false })
        CalSheet(editCal) { editCal = false }

        LlmSheet(editLlm, onDismiss = { editLlm = false }, onReady = {
            editLlm = false
            // 之前没出过纪要的补上
            if (Store.queueMissingSummaries() > 0) runCatching { com.xiaodeng.tinghui.RecService.process(ctx) }
        })
        FolderEditSheet(editFolder) { editFolder = null }
        Sheet(showTpls, { showTpls = false }) {
            T("纪要模板", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Group {
                templates.forEachIndexed { i, t ->
                    if (i > 0) Divider0()
                    ValueRow(t.name, if (t.id == defTpl) "默认" else "") {
                        showTpls = false
                        editTpl = t.id
                    }
                }
                Divider0()
                ValueRow("新建模板", "") {
                    showTpls = false
                    editTpl = NEW_TEMPLATE
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        TemplateSheet(editTpl) { editTpl = null }
    }
}

@Composable
private fun Section(text: String) {
    T(text, 13, Palette.Sub, FontWeight.SemiBold, Modifier.padding(start = 6.dp, top = 26.dp))
}

/** 一组设置下面的一行小字说明 */
@Composable
private fun Note(text: String) = T(text, 12, Palette.Sub, lineHeight = 17, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))

@Composable
private fun Divider0() = Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Palette.Faint))

@Composable
private fun ValueRow(title: String, value: String, accent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(title, 16, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
        if (value.isNotEmpty()) T(value, 14, if (accent) Palette.Accent else Palette.Sub, if (accent) FontWeight.SemiBold else FontWeight.Normal, tabular = true)
        Spacer(Modifier.width(6.dp))
        T("›", 20, Palette.Hint)
    }
}

@Composable
private fun PersonSheet(id: String?, onDismiss: () -> Unit) {
    val people by Voice.people.collectAsState()
    val p = people.firstOrNull { it.id == id }
    var name by remember(id) { mutableStateOf(p?.name ?: "") }
    var confirmDelete by remember(id) { mutableStateOf(false) }
    Sheet(id != null && p != null, onDismiss) {
        if (p == null) return@Sheet
        T(p.name, 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))

        Label("改名", Modifier.padding(top = 18.dp, start = 4.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(name, { name = it.take(12) }, "名字", Modifier.weight(1f), size = 17)
            Spacer(Modifier.width(10.dp))
            val dup = people.any { it.id != p.id && it.name == name.trim() }
            Pill(if (dup) "重名了" else "好", enabled = name.isNotBlank() && name.trim() != p.name && !dup, height = 50.dp, textSize = 15) {
                Voice.rename(p.id, name.trim())
                onDismiss()
            }
        }
        Pill(
            if (confirmDelete) "确定删掉${p.name}的声音" else "删掉这个人",
            Modifier.padding(top = 20.dp).fillMaxWidth(), dark = confirmDelete, height = 50.dp, textSize = 15,
        ) {
            if (!confirmDelete) confirmDelete = true
            else {
                Voice.delete(p.id)
                Store.forgetPerson(p.id)
                onDismiss()
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
