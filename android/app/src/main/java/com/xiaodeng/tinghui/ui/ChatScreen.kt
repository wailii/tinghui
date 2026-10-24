package com.xiaodeng.tinghui.ui

import com.xiaodeng.tinghui.BuildConfig

import android.content.ClipData
import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.Ask
import com.xiaodeng.tinghui.Chart
import com.xiaodeng.tinghui.Feishu
import com.xiaodeng.tinghui.Prefs
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.Status
import com.xiaodeng.tinghui.Store
import com.xiaodeng.tinghui.Tip
import com.xiaodeng.tinghui.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 从详情页点「小灯」进来的二级页：只问这场会，左上角返回 */
@Composable
fun ChatScreen(chatId: String, recIds: List<String>, onBack: () -> Unit, onOpenRec: (String) -> Unit, onOpenAt: ((String, Long) -> Unit)? = null) {
    ChatPane(chatId, recIds, bottomPad = null, onOpenRec = onOpenRec, onOpenAt = onOpenAt) {
        RoundIcon(R.drawable.ic_back, onClick = onBack)
        XiaoWaiLi(32.dp, Modifier.padding(start = 10.dp), wobble = false)
        T(BuildConfig.ASSISTANT, 18, Palette.Ink, FontWeight.Bold, Modifier.padding(start = 4.dp))
    }
}

/**
 * 对话页：中间一问一答，最下面输入框；问哪几场会像附件一样挂在输入框上面，随时加减。
 * bottomPad：放在首页页签里时，底栏占的高度（键盘弹出时底栏收起，输入框贴着键盘）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatPane(
    chatId: String,
    initialRecIds: List<String>,
    bottomPad: Dp?,
    onOpenRec: (String) -> Unit,
    /** 回答里的时间点：只问一场会时点了就去那场会的那一句 */
    onOpenAt: ((String, Long) -> Unit)? = null,
    header: @Composable RowScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val chats by Ask.chats.collectAsState()
    val live by Ask.live.collectAsState()
    val thinking by Ask.think.collectAsState()
    val recsAll by Store.recs.collectAsState()
    val saved = chats.firstOrNull { it.id == chatId }
    // 还没问过的新对话：选的会先放这里，第一次问的时候存
    var draftRecs by remember(chatId) { mutableStateOf(initialRecIds) }
    val chat = saved ?: Ask.draft(chatId, draftRecs)
    val recs = chat.recIds.mapNotNull { r -> recsAll.firstOrNull { it.id == r } }
    val writing = live[chatId]
    var input by remember(chatId) { mutableStateOf("") }
    var llmSetup by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    fun setRecs(ids: List<String>) {
        if (saved != null) Ask.save(saved.copy(recIds = ids)) else draftRecs = ids
    }

    fun ask(q: String) {
        val t = q.trim()
        if (t.isEmpty() || writing != null) return
        if (recs.isEmpty()) {
            picking = true
            return
        }
        if (!Prefs.llm.value.ready) {
            llmSetup = true
            return
        }
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        input = ""
        Ask.send(chat.copy(recIds = recs.map { it.id }), t)
    }

    val listState = rememberLazyListState()
    val count = chat.msgs.size + if (writing != null) 1 else 0
    LaunchedEffect(chatId, count) { if (count > 0) listState.animateScrollToItem(count - 1, 1_000_000) }
    LaunchedEffect(writing?.length) {
        // 边写边往下跟；你往上翻了就不跟
        if (writing != null) {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last != null && last.index >= info.totalItemsCount - 2) listState.scrollToItem(count - 1, 1_000_000)
        }
    }

    // 键盘弹出来了没有：看键盘实际占了多高
    val ime = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                header()
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (chat.msgs.isEmpty() && writing == null) Empty(recs.size) { ask(it) }
                else LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(chat.msgs, key = { i, _ -> "m$i" }) { i, m ->
                        when {
                            m.role == "user" -> UserBubble(m.text)
                            m.error -> ErrorCard(m.text, last = i == chat.msgs.lastIndex && writing == null) { Ask.retry(chat) }
                            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AnswerCard(m.text, recs.singleOrNull()?.let { r -> onOpenAt?.let { f -> { ms: Long -> f(r.id, ms) } } }) {
                                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("回答", Chart.flatten(m.text)))
                                    Tip.show("已复制")
                                }
                                // 小灯要做的事：确认了才做
                                m.acts.forEach { a ->
                                    ActCard(a, onOk = { Ask.runAct(chat.id, m.at, a.id) }, onSkip = { Ask.skipAct(chat.id, m.at, a.id) })
                                }
                            }
                        }
                    }
                    if (writing != null) item(key = "live") {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Card).padding(18.dp)) {
                            val th = thinking[chatId]
                            if (writing.isBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BusyDots()
                                    T(if (th.isNullOrBlank()) "在看这 ${recs.size} 场会" else "思考中", 15, Palette.Sub)
                                }
                                // 模型在想什么：灰着显示最近两行，让你知道它在动
                                if (!th.isNullOrBlank()) T(
                                    th.takeLast(160).replace("\n", " ").trim(), 13, Palette.Hint, lineHeight = 19,
                                    modifier = Modifier.padding(top = 8.dp), maxLines = 3,
                                )
                            } else {
                                MinutesView(writing, null)
                                Box(Modifier.padding(top = 8.dp)) { BusyDots() }
                            }
                        }
                    }
                }
            }
            // 输入区：上面挂着问哪几场会，下面输入框和发送
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.Bg)
                    .then(
                        if (bottomPad != null && !ime) Modifier.padding(bottom = bottomPad)
                        else Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recs.forEach { r ->
                        Row(
                            Modifier.pressable { onOpenRec(r.id) }.clip(RoundedCornerShape(50)).background(Palette.Card)
                                .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Ico(R.drawable.ic_meetings, 13.dp, Palette.Sub)
                            T(" ${r.title}", 12, Palette.Ink, FontWeight.SemiBold, maxLines = 1, modifier = Modifier.widthIn(max = 180.dp))
                            Box(Modifier.size(22.dp).pressable { setRecs(recs.map { it.id } - r.id) }, contentAlignment = Alignment.Center) {
                                Ico(R.drawable.ic_close, 11.dp, Palette.Sub)
                            }
                        }
                    }
                    Row(
                        Modifier.pressable { picking = true }.clip(RoundedCornerShape(50))
                            .border(1.dp, Palette.Line, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(if (recs.isEmpty()) "+ 选会议" else "+ 加会议", 12, Palette.Accent, FontWeight.SemiBold)
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Field(
                        input, { input = it.take(500) }, if (chat.msgs.isEmpty()) "问这${if (recs.size > 1) " ${recs.size} 场" else "场"}会" else "接着问",
                        Modifier.weight(1f), size = 16, singleLine = false,
                    )
                    Spacer(Modifier.width(10.dp))
                    val can = input.isNotBlank() && writing == null
                    Box(
                        Modifier.size(52.dp).pressable(enabled = can) { ask(input) }.clip(CircleShape)
                            .background(if (can) Palette.Ink else Palette.Faint),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (writing != null) BusyDots() else Ico(R.drawable.ic_send, 20.dp, if (can) Color.White else Palette.Hint)
                    }
                }
            }
        }
        PickSheet(picking, recsAll.filter { it.status == Status.Done && it.sentenceCount > 0 }, recs.map { it.id }, onDismiss = { picking = false }) {
            setRecs(it)
            picking = false
        }
        LlmSheet(llmSetup, onDismiss = { llmSetup = false }) { llmSetup = false }
    }
}

/** 还没问：中间几个常用问题，点一下就问 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Empty(n: Int, onAsk: (String) -> Unit) {
    val me = Voice.me()?.name ?: "我"
    val quick = when {
        n == 0 -> emptyList()
        n == 1 -> listOf("定了什么，谁去做", "还有哪些没定下来", "${me}要跟进哪些事", "把结论和待办发到群里")
        else -> listOf("这几场各定了什么", "所有待办按人列出来", "前后说法有变化的地方", "我的待办建成飞书任务")
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        XiaoWaiLi(120.dp)
        T(
            if (n == 0) "先在下面选几场会" else if (n == 1) "问这场会" else "问这 $n 场会",
            20, Palette.Ink, FontWeight.Bold, Modifier.padding(top = 10.dp),
        )
        if (n > 0) T("也能帮你发到飞书群、建任务", 14, Palette.Sub, modifier = Modifier.padding(top = 4.dp))
        FlowRow(
            Modifier.padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quick.forEach { q ->
                T(
                    q, 14, Palette.Ink, FontWeight.Medium,
                    Modifier.pressable { onAsk(q) }.clip(RoundedCornerShape(50)).background(Palette.Card)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun UserBubble(text: String) {
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        T(
            text, 15, Color.White, lineHeight = 23,
            modifier = Modifier
                .widthIn(max = maxW)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Ink)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun AnswerCard(text: String, onSeek: ((Long) -> Unit)?, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Card).padding(18.dp)) {
        MinutesView(text, onSeek)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            T("复制", 14, Palette.Sub, FontWeight.SemiBold, Modifier.pressable(onClick = onCopy).padding(8.dp))
        }
    }
}

/** 一件要做的事：写清楚发到哪、发什么，你点「确认」才做 */
@Composable
private fun ActCard(a: com.xiaodeng.tinghui.Agent.Act, onOk: () -> Unit, onSkip: () -> Unit) {
    val (title, body) = com.xiaodeng.tinghui.Agent.describe(a)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Card)
            .border(1.5.dp, if (a.state == "wait") Palette.Ink else Palette.Faint, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a.kind == "create_task") Ico(R.drawable.ic_todo, 20.dp, Palette.Ink) else Ico(R.drawable.ic_feishu, 20.dp, Color.Unspecified)
            Spacer(Modifier.width(8.dp))
            T(title, 15, Palette.Ink, FontWeight.Bold, Modifier.weight(1f), maxLines = 1)
        }
        T(body, 14, if (a.state == "skip") Palette.Hint else Palette.Ink, lineHeight = 21, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            when (a.state) {
                "wait" -> {
                    Pill("确认", height = 40.dp, textSize = 14, onClick = onOk)
                    Spacer(Modifier.width(14.dp))
                    T("取消", 14, Palette.Sub, FontWeight.SemiBold, Modifier.pressable(onClick = onSkip).padding(8.dp))
                }
                "run" -> {
                    BusyDots()
                    T("正在做", 14, Palette.Sub)
                }
                "done" -> {
                    Ico(R.drawable.ic_check, 16.dp, Palette.Good)
                    T(" " + a.result, 14, Palette.Good, FontWeight.SemiBold)
                }
                "fail" -> {
                    T(a.result, 13, Palette.AccentText, lineHeight = 19, modifier = Modifier.weight(1f))
                    T("重试", 14, Palette.Ink, FontWeight.SemiBold, Modifier.pressable(onClick = onOk).padding(8.dp))
                }
                else -> T("已取消", 14, Palette.Hint)
            }
        }
    }
}

@Composable
private fun ErrorCard(text: String, last: Boolean, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.AccentSoft).padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(text, 14, Palette.AccentText, lineHeight = 20, modifier = Modifier.weight(1f))
        if (last) T("重试", 14, Palette.Ink, FontWeight.SemiBold, Modifier.pressable(onClick = onRetry).padding(8.dp))
    }
}
