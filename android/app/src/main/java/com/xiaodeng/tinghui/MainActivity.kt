package com.xiaodeng.tinghui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.xiaodeng.tinghui.ui.ChatScreen
import com.xiaodeng.tinghui.ui.DetailScreen
import com.xiaodeng.tinghui.ui.FirstRecordSheet
import com.xiaodeng.tinghui.ui.HomeScreen
import com.xiaodeng.tinghui.ui.RecordScreen
import com.xiaodeng.tinghui.ui.SettingsScreen
import com.xiaodeng.tinghui.ui.TipHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_RECORD = "com.xiaodeng.tinghui.RECORD"
        const val EXTRA_REC = "rec"
        const val EXTRA_EVENT = "event"
        const val ACTION_ASK = "com.xiaodeng.tinghui.ASK"
        const val ACTION_TODO = "com.xiaodeng.tinghui.TODO"
        const val ACTION_IMPORT = "com.xiaodeng.tinghui.IMPORT"
    }

    private sealed interface Page {
        data object Home : Page
        data object Live : Page
        data object Settings : Page
        /** at：打开就从这儿放（待办里点 ▶ 时间点进来） */
        data class Detail(val id: String, val from: Page = Home, val at: Long = -1) : Page
        data class Chat(val id: String, val recIds: List<String>, val from: Page) : Page
    }

    private val page = mutableStateOf<Page>(Page.Home)
    /** 首页底部页签：0 会议，1 问 AI */
    private val homeTab = mutableStateOf(0)
    /** 问 AI 页签里正在聊的那段；askRecs 是新对话预先选好的会（首页多选过来的） */
    private val askChat = mutableStateOf(Ask.newId())
    private val askRecs = mutableStateOf<List<String>?>(null)
    private val firstSheet = mutableStateOf(false)

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) {
            RecService.start(this, pendingEvent)
            pendingEvent = null
            page.value = Page.Live
        } else {
            pendingEvent = null
            Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_LONG).show()
        }
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAudio(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { Root() }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // 有没转完的（上次被杀、没网、刚导入），回到前台时接着转；配了大模型但没出过纪要的补上
        Store.queueMissingSummaries()
        kotlin.concurrent.thread {
            Cal.sync(this)
            Shortcuts.update(this)
        }
        kotlin.concurrent.thread {
            // 待办变了的会，会议库里的待办行跟着更新
            val changed = Store.refreshAllTodos()
            if (Prefs.feishu.value.ready && Prefs.space.value.ready)
                changed.filter { (Store.get(it)?.fs?.syncedAt ?: 0) > 0 }.forEach { runCatching { FeishuSync.sync(it) } }
            Llm.backfillBriefs()
        }
        if ((Store.nextPending() != null || Store.nextVoice() != null || Store.nextSummary() != null) && !Live.working.value) {
            runCatching { RecService.process(this) }
        }
    }

    private fun handle(i: Intent?) {
        i ?: return
        if (i.action == ACTION_RECORD) {
            i.action = null
            val ev = i.getStringExtra(EXTRA_EVENT)
            i.removeExtra(EXTRA_EVENT)
            // 从开会提醒点进来的：收掉那条提醒
            if (ev != null) getSystemService(android.app.NotificationManager::class.java).cancel(ev.hashCode())
            if (!Live.state.value.recording) startRecording(skipTips = true, event = ev)
            page.value = Page.Live
        }
        when (i.action) {
            ACTION_ASK -> {
                i.action = null
                homeTab.value = 2
                page.value = Page.Home
            }
            ACTION_TODO -> {
                i.action = null
                homeTab.value = 1
                page.value = Page.Home
            }
            ACTION_IMPORT -> {
                i.action = null
                window.decorView.post { pickAudio.launch(arrayOf("audio/*")) }
            }
        }
        i.getStringExtra(EXTRA_REC)?.let {
            i.removeExtra(EXTRA_REC)
            if (Store.get(it) != null) page.value = Page.Detail(it)
        }
    }

    private var pendingEvent: String? = null

    private fun startRecording(skipTips: Boolean = false, event: String? = null) {
        // 先弹省电提示再开录的，日程要留到真正开录
        if (event != null || !skipTips) pendingEvent = event
        if (!skipTips && !Prefs.batteryTipDone.value) {
            firstSheet.value = true
            return
        }
        val need = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) {
            RecService.start(this, pendingEvent)
            pendingEvent = null
            page.value = Page.Live
        } else askPerms.launch(need.toTypedArray())
    }

    private fun openBattery() {
        val ok = runCatching { startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }.isSuccess
        // 这时已经跳到系统设置页了，用系统 Toast 才看得见
        if (ok) Toast.makeText(this, "进「后台耗电管理」→ 听会 → 允许后台耗电", Toast.LENGTH_LONG).show()
        else startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** 导入系统录音机等录的文件：拷进来，排队转写 */
    private fun importAudio(uri: Uri) {
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching {
                    var name = "导入的录音"
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { name = it }
                    }
                    val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { "m4a" }
                    val now = System.currentTimeMillis()
                    var id = Store.newId(now)
                    while (Store.get(id) != null) id += "_"
                    val dir = Store.dir(id).apply { mkdirs() }
                    val f = File(dir, "audio.$ext")
                    contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    val dur = MediaMetadataRetriever().run {
                        try {
                            setDataSource(f.path)
                            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } finally {
                            release()
                        }
                    }
                    Store.put(
                        Rec(
                            id, name.substringBeforeLast('.').ifBlank { Store.defaultTitle(now) }, now, dur,
                            f.name, imported = true, status = Status.Queued,
                        )
                    )
                    id
                }.getOrNull()
            }
            if (id == null) {
                Toast.makeText(this@MainActivity, "这个文件导不进来", Toast.LENGTH_SHORT).show()
            } else {
                // 拷贝期间切到了后台，前台服务起不来：下次回到 App 再转
                runCatching { RecService.process(this@MainActivity) }
                page.value = Page.Detail(id)
            }
        }
    }

    @Composable
    private fun Root() {
        val p = page.value
        val live by Live.state.collectAsState()
        // 录完（不管是在录音页点的结束，还是从通知栏结束）：在录音页的就跳到这场的详情，看转写进度
        var lastRec by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(live.recId) {
            val was = lastRec
            lastRec = live.recId
            if (live.recId == null && was != null && page.value == Page.Live) {
                page.value = if (Store.get(was) != null) Page.Detail(was) else Page.Home
            }
        }
        // 停在录音页却没在录（开麦失败、或者从通知栏结束后再打开）：等一下再回首页，别一开录就闪
        LaunchedEffect(p, live.recording) {
            if (p == Page.Live && !live.recording) {
                kotlinx.coroutines.delay(1500)
                if (page.value == Page.Live && !Live.state.value.recording) page.value = Page.Home
            }
        }
        val shown = p
        val back = {
            page.value = when (shown) {
                is Page.Chat -> shown.from
                is Page.Detail -> shown.from
                else -> Page.Home
            }
        }
        BackHandler(enabled = shown != Page.Home) { back() }
        BackHandler(enabled = shown == Page.Home && homeTab.value != 0) { homeTab.value = 0 }
        // 问这几场会：问过一模一样的几场就接着那段对话，没有就开新的
        val openAsk = { ids: List<String>, from: Page ->
            val old = Ask.chats.value.firstOrNull { it.recIds.toSet() == ids.toSet() }
            page.value = Page.Chat(old?.id ?: Ask.newId(), ids, from)
        }
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                shown,
                transitionSpec = {
                    val toLive = targetState == Page.Live
                    val fromLive = initialState == Page.Live
                    val forward = initialState == Page.Home || targetState is Page.Chat
                    when {
                        toLive -> (slideInVertically(tween(320)) { it } + fadeIn(tween(200))) togetherWith fadeOut(tween(250))
                        fromLive -> fadeIn(tween(220)) togetherWith (slideOutVertically(tween(300)) { it } + fadeOut(tween(250)))
                        forward -> (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(300)) { -it / 6 } + fadeOut(tween(200)))
                        else -> (slideInHorizontally(tween(300)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(200)))
                    }
                },
                label = "page",
            ) { s ->
                when (s) {
                    Page.Home -> HomeScreen(
                        onRecord = { startRecording() },
                        onOpenLive = { page.value = Page.Live },
                        onOpen = { page.value = Page.Detail(it) },
                        onSettings = { page.value = Page.Settings },
                        onImport = { pickAudio.launch(arrayOf("audio/*")) },
                        // 首页多选的几场：切到问 AI 页签开一段新对话
                        onAsk = {
                            askChat.value = Ask.newId()
                            askRecs.value = it
                            homeTab.value = 2
                        },
                        onRecordEvent = { startRecording(event = it) },
                        tab = homeTab.value,
                        onTab = { homeTab.value = it },
                        askChat = askChat.value,
                        askRecs = askRecs.value,
                        onNewChat = {
                            askChat.value = Ask.newId()
                            askRecs.value = null
                        },
                        onOpenChat = { askChat.value = it },
                        onOpenAt = { id, at -> page.value = Page.Detail(id, Page.Home, at) },
                    )
                    Page.Live -> RecordScreen(onCollapse = { page.value = Page.Home })
                    Page.Settings -> SettingsScreen(onBack = { page.value = Page.Home }, onBattery = { openBattery() })
                    is Page.Detail -> DetailScreen(s.id, onBack = { back() }, onAsk = { openAsk(it, s) }, startAt = s.at)
                    is Page.Chat -> ChatScreen(
                        s.id, s.recIds, onBack = { back() }, onOpenRec = { page.value = Page.Detail(it, s) },
                        onOpenAt = { id, at -> page.value = Page.Detail(id, s, at) },
                    )
                }
            }
            TipHost()
            FirstRecordSheet(
                firstSheet.value,
                onBattery = { openBattery() },
                onStart = {
                    firstSheet.value = false
                    Prefs.setBatteryTipDone()
                    startRecording(skipTips = true)
                },
                onDismiss = { firstSheet.value = false },
            )
        }
    }
}
