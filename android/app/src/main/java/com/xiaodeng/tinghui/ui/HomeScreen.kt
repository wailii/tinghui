package com.xiaodeng.tinghui.ui

import com.xiaodeng.tinghui.BuildConfig

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.Live
import com.xiaodeng.tinghui.Prefs
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.xiaodeng.tinghui.LiveState
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.Rec
import com.xiaodeng.tinghui.RecService
import com.xiaodeng.tinghui.Speaker
import com.xiaodeng.tinghui.Status
import com.xiaodeng.tinghui.Store
import com.xiaodeng.tinghui.clock
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun HomeScreen(
    onRecord: () -> Unit,
    onOpenLive: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onImport: () -> Unit,
    onAsk: (List<String>) -> Unit,
    onRecordEvent: (String) -> Unit,
    tab: Int,
    onTab: (Int) -> Unit,
    askChat: String,
    askRecs: List<String>?,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenAt: (String, Long) -> Unit,
) {
    val recs by Store.recs.collectAsState()
    val live by Live.state.collectAsState()
    val steps by Live.steps.collectAsState()
    val folders by Prefs.folders.collectAsState()
    var selRaw by remember { mutableStateOf<String?>(null) }
    // 选中的分类被改名或删了，回到「全部」
    val sel = selRaw?.takeIf { it == UNFILED || folders.any { f -> f.name == it } }
    var editing by remember { mutableStateOf<String?>(null) }
    var keySheet by remember { mutableStateOf(false) }
    // 长按进多选：选几场一起问 AI
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val picking = picked.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = picking) { picked = emptySet() }
    val apiKey by Prefs.apiKey.collectAsState()
    val all = recs.filter { it.id != live.recId }
    val list = when (sel) {
        null -> all
        UNFILED -> all.filter { it.folder == null }
        else -> all.filter { it.folder == sel }
    }
    val groups = remember(list) { list.groupBy { dayLabel(it.startedAt) } }
    // 飞书日历：接下来一天的会；每半分钟刷新一下「几分钟后」
    val events by com.xiaodeng.tinghui.Cal.events.collectAsState()
    val fresh by com.xiaodeng.tinghui.Cal.fresh.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val upcoming = if (picking) emptyList() else events.filter { it.end > now && it.start - now < 24 * 3600_000L }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var barH by remember { mutableIntStateOf(0) }
    val barDp = with(density) { barH.toDp() }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        // 0 会议 · 1 待办 · 2 小灯 · 3 我的
        if (tab == 1) TodoScreen(barDp, onOpenAt)
        else if (tab == 2) AskTab(askChat, askRecs, barDp, onNew = onNewChat, onOpen = onOpenChat, onOpenRec = onOpen, onOpenAt = onOpenAt)
        else if (tab == 3) MeScreen(barDp, onSettings)
        else LazyColumn(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp),
        ) {
            item {
                if (picking) Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T("已选 ${picked.size} 场", 28, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    T("取消", 16, Palette.Ink, FontWeight.SemiBold, Modifier.pressable { picked = emptySet() }.padding(10.dp))
                } else Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("听会", 28, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Row(
                        Modifier
                            .height(42.dp)
                            .pressable(onClick = onImport)
                            .clip(RoundedCornerShape(50))
                            .background(Palette.Card)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(R.drawable.ic_import, 18.dp)
                        T(" 导入", 14, Palette.Ink, FontWeight.SemiBold)
                    }
                }
                val month = remember(recs) { monthStats(recs) }
                if (month != null) T(month, 13, Palette.Sub, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                // 分享版第一次打开：没有转写用的 Key
                if (apiKey.isBlank()) {
                    Row(
                        Modifier
                            .padding(top = 16.dp)
                            .fillMaxWidth()
                            .pressable(scaleTo = 0.98f) { keySheet = true }
                            .clip(RoundedCornerShape(22.dp))
                            .background(Palette.AccentSoft)
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T("先填转写用的阿里百炼 Key", 15, Palette.Ink, FontWeight.SemiBold, Modifier.weight(1f))
                        T("去填", 14, Palette.Accent, FontWeight.SemiBold)
                    }
                }
                if (upcoming.isNotEmpty()) Schedule(upcoming, fresh, recs, now, recording = live.recording, onRecord = onRecordEvent)
                if (folders.isNotEmpty()) {
                    FolderBar(all, folders, sel, onSelect = {
                        selRaw = it
                        picked = emptySet()
                    }, onEdit = { editing = it })
                }
                Spacer(Modifier.height(18.dp))
                if (all.isEmpty()) EmptyHint()
                else if (list.isEmpty()) T("空的", 14, Palette.Sub, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
            }
            groups.forEach { (day, items) ->
                item(key = "h$day") {
                    T(day, 13, Palette.Sub, FontWeight.SemiBold, Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp))
                }
                items(items, key = { it.id }) { r ->
                    val can = r.status == com.xiaodeng.tinghui.Status.Done && r.sentenceCount > 0
                    RecCard(
                        r, steps[r.id], showFolder = sel == null,
                        picked = if (picking) r.id in picked else null,
                        onLong = if (can && !picking) ({ picked = setOf(r.id) }) else null,
                    ) {
                        when {
                            !picking -> onOpen(r.id)
                            r.id in picked -> picked = picked - r.id
                            can -> picked = picked + r.id
                            else -> com.xiaodeng.tinghui.Tip.show("这场还没转好")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            item { Spacer(Modifier.height(barDp + 30.dp)) }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Palette.Bg, Palette.Bg, Palette.Bg.copy(alpha = 0f))))
                .windowInsetsTopHeight(WindowInsets.statusBars)
        )

        FolderEditSheet(editing) { editing = null }
        KeySheet(keySheet) { keySheet = false }

        // 底部：会议 / 问 AI 两个页签，中间录音；多选时换成「问 AI」；在问 AI 里打字时收起
        val typing = WindowInsets.ime.getBottom(density) > 0
        if (!typing && Sheets.open.intValue == 0) Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { barH = it.height }
                // 小灯页底下紧挨着输入框：不要渐隐那一截，输入框才能贴近底栏
                .background(if (tab == 2) Brush.verticalGradient(listOf(Palette.Bg, Palette.Bg)) else Brush.verticalGradient(listOf(Palette.Bg.copy(alpha = 0f), Palette.Bg.copy(alpha = 0.92f), Palette.Bg)))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, top = if (tab == 2) 4.dp else 28.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (picking) {
                Pill("问${BuildConfig.ASSISTANT}（${picked.size} 场）", Modifier.fillMaxWidth()) {
                    val ids = all.filter { it.id in picked }.sortedBy { it.startedAt }.map { it.id }
                    picked = emptySet()
                    onAsk(ids)
                }
            } else {
                AnimatedVisibility(live.recording, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Box(Modifier.padding(bottom = 12.dp)) { LiveBar(live, onOpenLive) }
                }
                NavBar(tab, onTab, live.recording, onRecord, onOpenLive)
            }
        }
    }
}

/**
 * 日程：飞书日历里接下来一天的会。快开始的（半小时内）或正在开的，右边有「开录」；
 * 新出现的标「新」；多了收起来
 */
@Composable
private fun Schedule(
    events: List<com.xiaodeng.tinghui.Cal.Event>,
    fresh: Set<String>,
    recs: List<Rec>,
    now: Long,
    recording: Boolean,
    onRecord: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shown = if (open) events else events.take(3)
    val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    val today = java.time.LocalDate.now()
    Row(Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T("日程", 13, Palette.Sub, FontWeight.SemiBold, Modifier.weight(1f))
        T("飞书日历 · 接下来一天", 12, Palette.Hint)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.Card)) {
        shown.forEachIndexed { i, e ->
            if (i > 0) Box(Modifier.padding(start = 76.dp).fillMaxWidth().height(1.dp).background(Palette.Faint))
            val mins = (e.start - now) / 60_000
            val done = recs.any { it.eventUid == e.uid }
            val soon = !done && !recording && mins < 30
            val day = java.time.Instant.ofEpochMilli(e.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(50.dp)) {
                    T(fmt.format(e.start), 15, Palette.Ink, FontWeight.SemiBold, tabular = true)
                    T(if (day == today) "今天" else if (day == today.plusDays(1)) "明天" else "${day.monthValue}/${day.dayOfMonth}", 12, Palette.Sub)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (e.uid in fresh) {
                            T(
                                "新", 11, Color.White, FontWeight.Bold,
                                Modifier.padding(end = 6.dp).clip(RoundedCornerShape(5.dp)).background(Palette.Accent).padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                        T(e.title, 15, Palette.Ink, FontWeight.SemiBold, maxLines = 1)
                    }
                    T(
                        buildString {
                            when {
                                done -> append("已录")
                                mins <= 0 -> append("正在开")
                                mins < 60 -> append("$mins 分钟后")
                            }
                            if (e.people.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append("${e.people.size} 人")
                            }
                            if (e.location.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(e.location.substringAfterLast('-').substringBefore(" ("))
                            }
                        },
                        12, if (soon) Palette.Accent else Palette.Sub, modifier = Modifier.padding(top = 2.dp), maxLines = 1,
                    )
                }
                if (soon) {
                    Spacer(Modifier.width(8.dp))
                    Pill("开录", height = 40.dp, textSize = 14, haptic = HapticFeedbackConstants.CONFIRM) { onRecord(e.uid) }
                }
            }
        }
        if (events.size > 3) {
            T(
                if (open) "收起" else "还有 ${events.size - 3} 场", 13, Palette.Sub, FontWeight.SemiBold,
                Modifier.fillMaxWidth().pressable { open = !open }.padding(vertical = 12.dp), align = TextAlign.Center,
            )
        }
    }
}

/** 底栏：左「会议」右「问 AI」，中间录音（在录时是一颗跳动的红点，点了回录音页） */
@Composable
private fun NavBar(tab: Int, onTab: (Int) -> Unit, recording: Boolean, onRecord: () -> Unit, onOpenLive: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .shadow(12.dp, RoundedCornerShape(31.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
                .clip(RoundedCornerShape(31.dp))
                .background(Palette.Card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(Modifier.weight(1f), R.drawable.ic_meetings, "会议", tab == 0) { onTab(0) }
            NavItem(Modifier.weight(1f), R.drawable.ic_todo, "待办", tab == 1) { onTab(1) }
            Spacer(Modifier.width(84.dp))
            NavItem(Modifier.weight(1f), R.drawable.ic_xiaodeng, BuildConfig.ASSISTANT, tab == 2) { onTab(2) }
            NavItem(Modifier.weight(1f), R.drawable.ic_me, "我的", tab == 3) { onTab(3) }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .size(72.dp)
                .pressable(scaleTo = 0.9f, haptic = HapticFeedbackConstants.CONFIRM, onClick = if (recording) onOpenLive else onRecord)
                .shadow(12.dp, CircleShape, ambientColor = Color(0x33000000), spotColor = Color(0x44000000))
                .clip(CircleShape)
                .background(Palette.Island),
            contentAlignment = Alignment.Center,
        ) {
            if (recording) PulseDot(false, 22) else Box(Modifier.size(26.dp).clip(CircleShape).background(Palette.Rec))
        }
    }
}

@Composable
private fun NavItem(modifier: Modifier, icon: Int, label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().pressable(scaleTo = 0.94f, haptic = HapticFeedbackConstants.CLOCK_TICK, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Ico(icon, 22.dp, if (on) Palette.Ink else Palette.Hint)
        T(label, 11, if (on) Palette.Ink else Palette.Hint, FontWeight.SemiBold, Modifier.padding(top = 2.dp))
    }
}

/** 正在录：一条黑色长条，点开回到录音页 */
@Composable
private fun LiveBar(st: LiveState, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(st.paused) {
        while (!st.paused) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .pressable(scaleTo = 0.97f, onClick = onClick)
            .shadow(14.dp, RoundedCornerShape(50), ambientColor = Color(0x33000000), spotColor = Color(0x44000000))
            .clip(RoundedCornerShape(50))
            .background(Palette.Island)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(st.paused)
        Spacer(Modifier.width(10.dp))
        T(if (st.paused) "已暂停" else "正在录音", 16, Color.White, FontWeight.SemiBold)
        if (st.marks > 0) T("  ★ ${st.marks}", 14, Palette.Accent, FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        T(clock(st.elapsed(now)), 20, Color.White, FontWeight.Bold, tabular = true)
        Spacer(Modifier.width(10.dp))
        Ico(R.drawable.ic_up, 22.dp, Palette.IslandSub)
    }
}

@Composable
fun PulseDot(paused: Boolean, size: Int = 10) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dotA")
    Box(
        Modifier
            .size(size.dp)
            .alpha(if (paused) 0.5f else a)
            .clip(CircleShape)
            .background(if (paused) Palette.IslandSub else Palette.Rec)
    )
}

@Composable
private fun EmptyHint() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T("还没有录音", 16, Palette.Sub)
    }
}

@Composable
private fun RecCard(r: Rec, step: String?, showFolder: Boolean, picked: Boolean?, onLong: (() -> Unit)?, onClick: () -> Unit) {
    val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(r.startedAt)
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.98f, onLongClick = onLong, onClick = onClick)
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Card)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(r.title, 17, Palette.Ink, FontWeight.SemiBold, maxLines = 1)
                T(
                    "$time · ${durationText(r.durationMs)}" + if (showFolder && r.folder != null) " · ${r.folder}" else "",
                    13, Palette.Sub, modifier = Modifier.padding(top = 3.dp), tabular = true, maxLines = 1,
                )
            }
            if (picked != null) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (picked) Palette.Accent else Palette.Card)
                        .border(2.dp, if (picked) Palette.Accent else Palette.Line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (picked) Ico(R.drawable.ic_check, 16.dp, Color.White) }
            } else if (r.status == Status.Done && r.speakers.isNotEmpty()) Avatars(r.speakers)
        }
        when {
            r.busy -> Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BusyDots()
                T(step ?: if (r.status == Status.Queued) "排队中" else "转写中", 13, Palette.Accent, FontWeight.Medium, maxLines = 1)
            }
            r.status == Status.Failed -> T(
                "没转成：${r.error ?: "未知原因"}", 13, Palette.Accent, FontWeight.Medium,
                Modifier.padding(top = 10.dp), maxLines = 2,
            )
            r.status == Status.Done && r.sentenceCount == 0 -> T(
                "没听到有人说话", 13, Palette.Hint, FontWeight.Medium, Modifier.padding(top = 10.dp),
            )
            r.status == Status.Done -> {
                if (r.summarizing) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        BusyDots()
                        T("正在生成纪要", 13, Palette.Accent, FontWeight.Medium, maxLines = 1)
                    }
                }
                // 大模型写的一句话摘要；还没有就先放开头几句
                if (r.brief.isNotBlank()) {
                    T(r.brief, 14, Palette.Ink.copy(alpha = 0.78f), modifier = Modifier.padding(top = 10.dp), maxLines = 2, lineHeight = 21)
                } else if (r.preview.isNotBlank() && !r.summarizing) {
                    T(r.preview, 14, Palette.Sub, modifier = Modifier.padding(top = 10.dp), maxLines = 2, lineHeight = 21)
                }

            }
        }
    }
}

/** 说话人头像：名字的第一个字，没认出来的是问号 */
@Composable
fun Avatars(speakers: List<Speaker>, max: Int = 4) {
    val shown = speakers.sortedByDescending { it.talkMs }.take(max)
    val extra = speakers.size - shown.size
    Box(Modifier.height(30.dp).width((shown.size * 20 + 10 + if (extra > 0) 24 else 0).dp)) {
        shown.forEachIndexed { i, s ->
            Avatar(s, 30, Modifier.offset(x = (i * 20).dp))
        }
        if (extra > 0) {
            T("+$extra", 12, Palette.Sub, FontWeight.SemiBold, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
fun Avatar(s: Speaker, size: Int, modifier: Modifier = Modifier) {
    val name = if (Store.known(s)) Store.speakerName(s) else null
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Palette.speaker(s, s.idx))
            .border(2.dp, Palette.Card, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        T(name?.take(1) ?: "${s.idx + 1}", (size * 0.42f).toInt(), Color.White, FontWeight.Bold)
    }
}

@Composable
fun BusyDots() {
    val t = rememberInfiniteTransition(label = "busy")
    Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.2f, 1f,
                infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse),
                label = "b$i",
            )
            Box(Modifier.size(5.dp).alpha(a).clip(CircleShape).background(Palette.Accent))
        }
    }
}

fun durationText(ms: Long): String {
    val m = (ms + 30_000) / 60_000
    return when {
        ms < 60_000 -> "${ms / 1000} 秒"
        m < 60 -> "$m 分钟"
        m % 60 == 0L -> "${m / 60} 小时"
        else -> "${m / 60} 小时 ${m % 60} 分"
    }
}

private fun dayLabel(t: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    val diff = java.time.temporal.ChronoUnit.DAYS.between(day, java.time.LocalDate.now(zone))
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val week = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[c.get(Calendar.DAY_OF_WEEK) - 1]
    return when (diff) {
        0L -> "今天"
        1L -> "昨天"
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(t) + " " + week
    }
}


private fun monthStats(recs: List<Rec>): String? {
    val c = Calendar.getInstance()
    c.set(Calendar.DAY_OF_MONTH, 1)
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    val mine = recs.filter { it.startedAt >= c.timeInMillis && it.status != Status.Recording }
    if (mine.isEmpty()) return null
    val hours = mine.sumOf { it.durationMs } / 3600_000.0
    val cost = mine.sumOf { it.costYuan }
    val h = if (hours < 1) "${(hours * 60).toInt()} 分钟" else "%.1f 小时".format(hours)
    return "本月 ${mine.size} 场 · $h · 转写花了 ¥%.2f".format(cost)
}

const val UNFILED = "\u0000unfiled"

/** 分类一排：全部、各个分类、未分类。点一下只看这一类，长按改名或删掉 */
@Composable
private fun FolderBar(
    all: List<Rec>,
    folders: List<Prefs.Folder>,
    sel: String?,
    onSelect: (String?) -> Unit,
    onEdit: (String) -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val unfiled = all.count { it.folder == null }
    Row(
        Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        @Composable
        fun chip(key: String?, label: String, n: Int, editable: Boolean) {
            val on = sel == key
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Palette.Ink else Palette.Card)
                    .pointerInput(key, editable) {
                        detectTapGestures(
                            onTap = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onSelect(key)
                            },
                            onLongPress = {
                                if (editable && key != null) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    onEdit(key)
                                }
                            },
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (key != null && key != UNFILED) {
                    Ico(R.drawable.ic_folder, 14.dp, if (on) Color.White else Palette.Accent)
                    Spacer(Modifier.width(5.dp))
                }
                T(label, 14, if (on) Color.White else Palette.Ink, FontWeight.SemiBold)
                T(" $n", 13, if (on) Palette.IslandSub else Palette.Sub, tabular = true)
            }
        }
        chip(null, "全部", all.size, false)
        folders.forEach { f -> chip(f.name, f.name, all.count { it.folder == f.name }, true) }
        if (unfiled > 0) chip(UNFILED, "未分类", unfiled, false)
    }
}
