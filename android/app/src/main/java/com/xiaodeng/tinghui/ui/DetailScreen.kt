package com.xiaodeng.tinghui.ui

import com.xiaodeng.tinghui.BuildConfig

import androidx.compose.foundation.gestures.detectHorizontalDragGestures

import android.content.ClipData
import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaodeng.tinghui.Exporter
import com.xiaodeng.tinghui.Feishu
import com.xiaodeng.tinghui.Live
import com.xiaodeng.tinghui.Mark
import com.xiaodeng.tinghui.Para
import com.xiaodeng.tinghui.Prefs
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.Rec
import com.xiaodeng.tinghui.RecService
import com.xiaodeng.tinghui.Sentence
import com.xiaodeng.tinghui.Speaker
import com.xiaodeng.tinghui.Status
import com.xiaodeng.tinghui.Store
import com.xiaodeng.tinghui.Tip
import com.xiaodeng.tinghui.Voice
import com.xiaodeng.tinghui.clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

private sealed interface Line {
    data class P(val p: Para) : Line
    data class M(val m: Mark) : Line
}


private val CurBg = Color(0xFFFFE3D1)
private val HitBg = Color(0xFFFFE680)

@Composable
fun DetailScreen(id: String, onBack: () -> Unit, onAsk: (List<String>) -> Unit, startAt: Long = -1) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val recs by Store.recs.collectAsState()
    val people by Voice.people.collectAsState()
    val steps by Live.steps.collectAsState()
    val rec = recs.firstOrNull { it.id == id }
    if (rec == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val sentences by produceState(
        initialValue = emptyList<Sentence>(),
        id, rec.sentenceCount, rec.voiceDone, rec.alias.size, rec.status,
    ) { value = withContext(Dispatchers.IO) { Store.sentences(id) } }
    val lines = remember(sentences, rec.marks) {
        val out = ArrayList<Line>()
        val marks = rec.marks.sortedBy { it.atMs }.toMutableList()
        for (p in Exporter.paragraphs(sentences)) {
            while (marks.isNotEmpty() && marks.first().atMs <= p.begin) out += Line.M(marks.removeAt(0))
            out += Line.P(p)
        }
        marks.forEach { out += Line.M(it) }
        out
    }
    val names = remember(rec, people) { rec.speakers.associate { it.idx to Store.speakerName(it) } }
    val colorOf: (Int) -> Color = remember(rec, people) {
        val m = rec.speakers.associate { it.idx to Palette.speaker(it, it.idx) }
        ({ idx: Int -> m[idx] ?: Palette.speaker(null, idx) })
    }

    // 转写稿 / 纪要：进来先看转写稿
    val llm by Prefs.llm.collectAsState()
    var tabPick by remember(id) { mutableIntStateOf(-1) }
    val hasText = rec.status == Status.Done && sentences.isNotEmpty()
    val tab = if (!hasText || tabPick < 0) 1 else tabPick
    // 左右滑的手势只装一次，用这个拿到当前在哪个页签
    val tabNow by androidx.compose.runtime.rememberUpdatedState(tab)
    var folderPick by remember { mutableStateOf(false) }
    var llmSetup by remember { mutableStateOf(false) }
    var pickTpl by remember { mutableStateOf(false) }
    fun summarize(tpl: String? = null) {
        Store.update(id) { it.copy(sumState = com.xiaodeng.tinghui.SumState.Queued, sumError = null, sumTemplate = tpl ?: it.sumTemplate) }
        RecService.process(ctx)
    }

    // ---------- 播放 ----------
    val player = remember(id) { PlayerState(Store.audio(rec), rec.durationMs) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.tick()
            delay(80)
        }
    }
    var scrub by remember { mutableStateOf<Long?>(null) }
    val shown = scrub ?: player.pos
    val engaged = player.playing || scrub != null || player.pos > 0
    val activeLine = if (!engaged) -1 else lines.indexOfLast { it is Line.P && it.p.begin <= shown + 300 }

    // ---------- 跟随：播放/拖动时转写稿自动滚到正在说的那段；手动划走就不跟了 ----------
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged && player.playing) follow = false }
    val lead = with(density) { 90.dp.roundToPx() }
    val topBar = with(density) { 70.dp.roundToPx() }
    LaunchedEffect(activeLine, follow, scrub != null) {
        if (activeLine < 0 || !follow || player.clipEnd >= 0 || tab != 1) return@LaunchedEffect
        if (scrub != null) {
            // 拖动时位置变得很快，用不挂起的跳转，跟得上手指
            listState.requestScrollToItem(1 + activeLine, -lead)
        } else if (player.playing) {
            // 正在放的那段还在屏幕舒服的位置就不动，快出去了才滚
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == 1 + activeLine }
            val h = info.viewportEndOffset
            if (item == null || item.offset < topBar || item.offset + item.size > h * 0.62f) {
                listState.animateScrollToItem(1 + activeLine, -lead)
            }
        }
    }
    fun seekTo(ms: Long, play: Boolean = true) {
        follow = true
        player.cancelClip()
        player.seek(ms, play)
    }
    // 从待办点 ▶ 进来：直接在转写稿里放那一句
    LaunchedEffect(id, startAt) {
        if (startAt >= 0) {
            tabPick = 1
            delay(400)
            seekTo(startAt)
        }
    }

    // ---------- 搜索 ----------
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val hits = remember(query, lines) {
        if (query.isBlank()) emptyList() else lines.indices.filter { (lines[it] as? Line.P)?.p?.text?.contains(query, true) == true }
    }
    var hitAt by remember { mutableIntStateOf(0) }
    LaunchedEffect(hits) { hitAt = 0 }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    LaunchedEffect(hitAt, hits) {
        hits.getOrNull(hitAt)?.let {
            follow = false
            listState.animateScrollToItem(1 + it, -lead)
        }
    }

    // ---------- 会后收尾 ----------
    var wrap by remember { mutableStateOf(false) }

    var naming by remember { mutableStateOf<Int?>(null) }
    // 长按的那一段：复制或者把原话录音发到群
    var longPara by remember { mutableStateOf<com.xiaodeng.tinghui.Para?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dockH by remember { mutableIntStateOf(0) }
    val dockDp = with(density) { dockH.toDp() }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                // 左右滑切转写稿和纪要：往左滑看纪要，往右滑回转写稿
                .pointerInput(hasText) {
                    if (!hasText) return@pointerInput
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = {
                            val far = 64.dp.toPx()
                            if (dx < -far && tabNow == 1) tabPick = 0
                            else if (dx > far && tabNow == 0) tabPick = 1
                        },
                    ) { _, d -> dx += d }
                },
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 60.dp, bottom = dockDp + 30.dp),
        ) {
            item {
                Row(
                    Modifier.padding(start = 4.dp, top = 14.dp).pressable(scaleTo = 0.98f) { renaming = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(rec.title, 26, Palette.Ink, FontWeight.Bold, Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Ico(R.drawable.ic_edit, 18.dp, Palette.Hint)
                }
                val date = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(rec.startedAt)
                T(
                    buildString {
                        append("$date · ${durationText(rec.durationMs)}")
                        if (rec.speakers.isNotEmpty()) append(" · ${rec.speakers.size} 人")
                        if (rec.costYuan > 0) append(" · 转写 ¥%.2f".format(rec.costYuan))
                    },
                    13, Palette.Sub, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
                // 分类：点一下换；旁边是问 AI，点了进这场会的问答
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .pressable { folderPick = true }
                        .clip(RoundedCornerShape(50))
                        .background(if (rec.folder != null) Palette.Card else Palette.Faint)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(R.drawable.ic_folder, 15.dp, if (rec.folder != null) Palette.Accent else Palette.Sub)
                    T(
                        " ${rec.folder ?: if (rec.summarizing) "分类中…" else "未分类"}",
                        13, if (rec.folder != null) Palette.Ink else Palette.Sub, FontWeight.SemiBold,
                    )
                    Ico(R.drawable.ic_down, 16.dp, Palette.Hint)
                }
                if (hasText) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        Modifier
                            .pressable { onAsk(listOf(id)) }
                            .clip(RoundedCornerShape(50))
                            .background(Palette.Ink)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(R.drawable.ic_xiaodeng, 16.dp, Color.White)
                        T(" " + BuildConfig.ASSISTANT, 13, Color.White, FontWeight.SemiBold)
                    }
                }
                }

                when {
                    rec.busy -> Progress(rec, steps[id])
                    rec.status == Status.Failed -> Failed(rec) {
                        Store.update(id) { it.copy(status = Status.Queued, error = null) }
                        RecService.process(ctx)
                    }
                }

                if (rec.speakers.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = 18.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val total = rec.speakers.sumOf { it.talkMs }.coerceAtLeast(1)
                        rec.speakers.sortedByDescending { it.talkMs }.forEach { s ->
                            SpeakerChip(s, (s.talkMs * 100 / total).toInt()) { naming = s.idx }
                        }
                    }
                }
                if (hasText) {
                    Box(Modifier.padding(top = 18.dp)) {
                        SegmentPicker(
                            listOf(Triple(1, "转写稿", ""), Triple(0, if (rec.summarizing) "纪要生成中" else "纪要", "")),
                            tab,
                        ) { tabPick = it }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            if (tab == 0) item(key = "minutes") {
                MinutesPane(
                    rec = rec,
                    llmReady = llm.ready,
                    step = steps[id],
                    onSeek = { seekTo(it) },
                    onSetup = { llmSetup = true },
                    onGenerate = { if (rec.sumState == com.xiaodeng.tinghui.SumState.Failed) summarize() else pickTpl = true },
                    onCopy = { com.xiaodeng.tinghui.Exporter.copyMinutes(ctx, rec) },
                )
            }
            if (tab == 1) itemsIndexed(lines, key = { i, l -> if (l is Line.P) "p$i" else "m$i" }) { i, l ->
                when (l) {
                    is Line.M -> MarkRow(l.m) { seekTo(l.m.atMs) }
                    is Line.P -> ParaRow(
                        p = l.p,
                        name = names[l.p.spk] ?: "说话人 ${l.p.spk + 1}",
                        speaker = rec.speakers.firstOrNull { it.idx == l.p.spk },
                        color = colorOf(l.p.spk),
                        active = i == activeLine,
                        pos = shown,
                        query = if (searching) query else "",
                        onTap = { s -> seekTo(s.begin) },
                        onName = { naming = l.p.spk },
                        onLong = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            longPara = l.p
                        },
                    )
                }
            }
            item {
                if (rec.status == Status.Done && sentences.isEmpty()) {
                    var sure by remember { mutableStateOf(false) }
                    Column(
                        Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Palette.Card)
                            .padding(18.dp)
                    ) {
                        T("没听到有人说话", 17, Palette.Ink, FontWeight.SemiBold)
                        Pill(
                            if (sure) "确定删掉" else "删掉这条",
                            Modifier.padding(top = 14.dp).fillMaxWidth(),
                            dark = sure, color = if (sure) Palette.Rec else null, height = 46.dp, textSize = 15,
                        ) {
                            if (!sure) sure = true
                            else {
                                player.release()
                                Store.delete(id)
                                Tip.show("删掉了")
                                onBack()
                            }
                        }
                    }
                }
            }
        }

        // 顶栏：固定不动
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0.82f to Palette.Bg, 1f to Palette.Bg.copy(alpha = 0f)))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp)
        ) {
            AnimatedContent(searching, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "top") { s ->
                if (!s) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIcon(R.drawable.ic_back, onClick = onBack)
                        Spacer(Modifier.weight(1f))
                        RoundIcon(R.drawable.ic_search, enabled = sentences.isNotEmpty()) {
                            tabPick = 1
                            searching = true
                        }
                        Spacer(Modifier.width(8.dp))
                        RoundIcon(R.drawable.ic_more) { menu = true }
                        Spacer(Modifier.width(8.dp))
                        WrapButton(enabled = rec.status == Status.Done && sentences.isNotEmpty()) { wrap = true }
                    }
                } else {
                    SearchBar(query, { query = it }, hits.size, hitAt, onPrev = {
                        if (hits.isNotEmpty()) hitAt = (hitAt - 1 + hits.size) % hits.size
                    }, onNext = {
                        if (hits.isNotEmpty()) hitAt = (hitAt + 1) % hits.size
                    }) {
                        searching = false
                        query = ""
                    }
                }
            }
        }

        // 手动划走后，给个回去的按钮
        AnimatedVisibility(
            !follow && player.playing && !searching,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = dockDp + 14.dp),
            enter = fadeIn() + scaleIn(spring(0.6f, 500f), 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            Row(
                Modifier
                    .pressable { follow = true }
                    .shadow(10.dp, RoundedCornerShape(50), ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Island)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(R.drawable.ic_down, 18.dp, Color.White)
                T(" 回到正在播的地方", 14, Color.White, FontWeight.SemiBold)
            }
        }

        // 拖进度条时：浮出那一刻是谁、在说什么
        val at = scrub
        AnimatedVisibility(
            at != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = dockDp + 12.dp, start = 16.dp, end = 16.dp),
            enter = fadeIn(tween(120)) + slideInVertically { it / 4 },
            exit = fadeOut(tween(150)),
        ) {
            val s = at?.let { t -> sentences.lastOrNull { it.begin <= t } }
            Column(
                Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.Island)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(clock(at ?: 0), 15, Color.White, FontWeight.Bold, tabular = true)
                    if (s != null) {
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colorOf(s.spk)))
                        T(" ${names[s.spk] ?: ""}", 14, Palette.IslandSub, FontWeight.SemiBold)
                    }
                }
                if (s != null) T(s.text, 14, Color.White, modifier = Modifier.padding(top = 4.dp), maxLines = 2, lineHeight = 20)
            }
        }

        PlayerDock(
            player = player,
            sentences = sentences,
            colors = colorOf,
            marks = rec.marks,
            scrub = scrub,
            onScrub = { scrub = it; if (it != null) follow = true },
            onSeekDone = { seekTo(it) },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { dockH = it.height },
        )

        val sp = rec.speakers.firstOrNull { it.idx == naming }
        NameSheet(
            rec = rec,
            sp = sp,
            visible = naming != null && sp != null,
            player = player,
            clip = { s -> sentences.filter { it.spk == s.idx }.maxByOrNull { it.end - it.begin } },
            onDismiss = {
                player.stopClip()
                naming = null
            },
        )
        RenameSheet(renaming, rec) { renaming = false }
        FolderPickSheet(folderPick, rec) { folderPick = false }
        TemplatePickSheet(pickTpl, rec.sumTemplate, onDismiss = { pickTpl = false }) { t ->
            pickTpl = false
            tabPick = 0
            summarize(t)
        }
        LlmSheet(llmSetup, onDismiss = { llmSetup = false }) {
            llmSetup = false
            tabPick = 0
            Store.queueMissingSummaries()
            summarize()
        }
        MenuSheet(menu, rec, sentences.isNotEmpty(), onDismiss = { menu = false }, onRename = {
            menu = false
            renaming = true
        }, onRegenerate = {
            menu = false
            tabPick = 0
            if (rec.summarizing) Tip.show("正在生成，等一下")
            else if (llm.ready) pickTpl = true else llmSetup = true
        }, onDeleted = {
            menu = false
            player.release()
            Store.delete(id)
            onBack()
        })
        WrapSheet(wrap, rec) { wrap = false }
        ClipSheet(longPara, longPara?.let { names[it.spk] } ?: "", rec) { longPara = null }
    }
}

/** 右上角「发送」：把这场会的结论和待办发到飞书群 */
@Composable
private fun WrapButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(42.dp)
            .pressable(enabled = enabled, onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(Palette.Card)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(R.drawable.ic_feishu, 22.dp, if (enabled) Color.Unspecified else Palette.Hint)
        Spacer(Modifier.width(6.dp))
        T("发送", 14, if (enabled) Palette.Ink else Palette.Hint, FontWeight.SemiBold)
    }
}

@Composable
private fun SearchBar(
    q: String,
    onQ: (String) -> Unit,
    count: Int,
    at: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { focus.requestFocus() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(R.drawable.ic_close, onClick = onClose)
        Spacer(Modifier.width(8.dp))
        Field(q, onQ, "搜转写稿里的词", Modifier.weight(1f), size = 15, focus = focus)
        Spacer(Modifier.width(8.dp))
        T(if (q.isBlank()) "" else if (count == 0) "没有" else "${at + 1}/$count", 13, Palette.Sub, tabular = true)
        RoundIcon(R.drawable.ic_up, size = 36.dp, icon = 20.dp, bg = Color.Transparent, enabled = count > 0, onClick = onPrev)
        RoundIcon(R.drawable.ic_down, size = 36.dp, icon = 20.dp, bg = Color.Transparent, enabled = count > 0, onClick = onNext)
    }
}

/** 转写中：一行字 + 跳动的点，上传有百分比、转写有计时 */
@Composable
private fun Progress(rec: Rec, step: String?) {
    Row(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.Card)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BusyDots()
        T(
            step ?: when (rec.status) {
                Status.Queued -> "排队中"
                Status.Uploading -> "上传中"
                else -> "转写中"
            },
            15, Palette.Ink, FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Failed(rec: Rec, onRetry: () -> Unit) {
    Row(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.AccentSoft)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T("没转成", 16, Palette.Ink, FontWeight.SemiBold)
            T(rec.error ?: "", 13, Palette.AccentText, lineHeight = 19, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Pill("重试", height = 42.dp, textSize = 14, onClick = onRetry)
    }
}

@Composable
private fun SpeakerChip(s: Speaker, pct: Int, onClick: () -> Unit) {
    val guess = Store.guessName(s)
    Row(
        Modifier
            .pressable(onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(if (guess != null) Palette.AccentSoft else Palette.Card)
            .padding(start = 5.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(s, 30)
        Spacer(Modifier.width(8.dp))
        Column {
            T(
                guess?.let { "$it？" } ?: Store.speakerName(s), 14,
                if (!Store.known(s) && guess == null) Palette.Sub else Palette.Ink, FontWeight.SemiBold,
            )
            T("$pct%", 11, Palette.Sub, tabular = true)
        }
    }
}

@Composable
private fun MarkRow(m: Mark, onClick: () -> Unit) {
    if (m.gap) {
        // 这段被通话或别的 App 占了麦克风
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("${clock(m.atMs)} · 没录到 ${clock(m.endMs - m.atMs)}", 13, Palette.Sub, FontWeight.Medium, tabular = true)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Palette.Line))
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.98f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(R.drawable.ic_star, 15.dp, Palette.Accent)
        T(" ${clock(m.atMs)}", 13, Palette.Accent, FontWeight.SemiBold, tabular = true)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Palette.AccentSoft))
    }
}

/** 一段话：点哪句从哪句开始放；正在放的那句底色高亮；长按复制这段 */
@Composable
private fun ParaRow(
    p: Para,
    name: String,
    speaker: Speaker?,
    color: Color,
    active: Boolean,
    pos: Long,
    query: String,
    onTap: (Sentence) -> Unit,
    onName: () -> Unit,
    onLong: () -> Unit,
) {
    val view = LocalView.current
    val tapNow by rememberUpdatedState(onTap)
    val longNow by rememberUpdatedState(onLong)
    val starts = remember(p) {
        var acc = 0
        p.parts.map { s -> acc.also { acc += s.text.length } }
    }
    val cur = if (active) p.parts.indexOfLast { it.begin <= pos + 300 }.coerceAtLeast(0) else -1
    val text: AnnotatedString = remember(p, cur, query) {
        buildAnnotatedString {
            append(p.text)
            if (cur >= 0) {
                val a = starts[cur]
                addStyle(SpanStyle(background = CurBg), a, a + p.parts[cur].text.length)
            }
            if (query.isNotBlank()) {
                var i = p.text.indexOf(query, ignoreCase = true)
                while (i >= 0) {
                    addStyle(SpanStyle(background = HitBg, fontWeight = FontWeight.SemiBold), i, i + query.length)
                    i = p.text.indexOf(query, i + query.length, ignoreCase = true)
                }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) Palette.Card else Color.Transparent)
            .pointerInput(p) {
                detectTapGestures(
                    onTap = {
                        tick()
                        tapNow(p.parts[0])
                    },
                    onLongPress = { longNow() },
                )
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.pressable(scaleTo = 0.94f, onClick = onName),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (speaker != null) Avatar(speaker, 20) else Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(7.dp))
                T(name, 13, color, FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
            T(clock(p.begin), 12, Palette.Hint, tabular = true)
        }
        // 点在哪句就从哪句开始放
        BasicText(
            text,
            Modifier
                .padding(top = 4.dp)
                .pointerInput(p) {
                    detectTapGestures(
                        onTap = { o ->
                            tick()
                            val off = layout?.getOffsetForPosition(o) ?: 0
                            tapNow(p.parts[starts.indexOfLast { it <= off }.coerceAtLeast(0)])
                        },
                        onLongPress = { longNow() },
                    )
                },
            style = TextStyle(fontSize = 16.sp, color = Palette.Ink, lineHeight = 27.sp),
            onTextLayout = { layout = it },
        )
    }
}

/** 更多：改名、复制、用别的 App 分享、删除（删除要再点一次确认） */
@Composable
private fun MenuSheet(
    visible: Boolean,
    rec: Rec,
    hasText: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onRegenerate: () -> Unit,
    onDeleted: () -> Unit,
) {
    val ctx = LocalContext.current
    var confirm by remember(visible) { mutableStateOf(false) }
    Sheet(visible, onDismiss) {
        T(rec.title, 20, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp), maxLines = 1)
        Group {
            MenuRow(R.drawable.ic_edit, "改会议名", onClick = onRename)
            Divider()
            if (hasText) {
                MenuRow(R.drawable.ic_edit, if (rec.summary.isNotBlank()) "重新生成纪要" else "生成纪要", onClick = onRegenerate)
                Divider()
                if (rec.summary.isNotBlank()) {
                    MenuRow(R.drawable.ic_copy, "复制纪要") {
                        com.xiaodeng.tinghui.Exporter.copyMinutes(ctx, rec)
                        onDismiss()
                    }
                    Divider()
                }
                MenuRow(R.drawable.ic_copy, "复制全文") {
                    Exporter.copyAll(ctx, rec)
                    onDismiss()
                }
                Divider()
                MenuRow(R.drawable.ic_share, "用其他 App 分享") {
                    Exporter.shareTranscript(ctx, rec)
                    onDismiss()
                }
                Divider()
            }
            MenuRow(R.drawable.ic_mic, "分享录音文件") {
                Exporter.shareAudio(ctx, rec)
                onDismiss()
            }
        }
        Group {
            AnimatedContent(confirm, label = "del") { c ->
                if (!c) MenuRow(R.drawable.ic_delete, "删除这场录音", Palette.Accent) { confirm = true }
                else Column(Modifier.padding(16.dp)) {
                    T("删掉后找不回来", 14, Palette.Sub)
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("算了", Modifier.weight(1f), dark = false, height = 46.dp, textSize = 15) { confirm = false }
                        Pill("删掉", Modifier.weight(1f), height = 46.dp, textSize = 15, color = Palette.Rec, onClick = onDeleted)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 纪要页签 */
@Composable
private fun MinutesPane(
    rec: Rec,
    llmReady: Boolean,
    step: String?,
    onSeek: (Long) -> Unit,
    onSetup: () -> Unit,
    onGenerate: () -> Unit,
    onCopy: () -> Unit,
) {
    val card = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(22.dp))
        .background(Palette.Card)
        .padding(18.dp)
    when {
        rec.summarizing -> Row(card, verticalAlignment = Alignment.CenterVertically) {
            BusyDots()
            T("正在生成纪要", 15, Palette.Ink, FontWeight.SemiBold)
        }
        rec.sumState == com.xiaodeng.tinghui.SumState.Failed -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.AccentSoft)
                .padding(18.dp)
        ) {
            T("纪要没生成出来", 16, Palette.Ink, FontWeight.SemiBold)
            T(rec.sumError ?: "", 13, Palette.AccentText, lineHeight = 19, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("重试", Modifier.weight(1f), height = 44.dp, textSize = 15, onClick = onGenerate)
                Pill("模型设置", Modifier.weight(1f), dark = false, height = 44.dp, textSize = 15, onClick = onSetup)
            }
        }
        rec.summary.isNotBlank() -> Column(card) {
            MinutesView(Store.minutes(rec), onSeek)
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(1.dp).background(Palette.Faint))
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                T(
                    listOf(Prefs.templates.value.firstOrNull { it.id == rec.sumTemplate }?.name, rec.sumModel.ifBlank { null })
                        .filterNotNull().joinToString(" · "),
                    12, Palette.Hint, modifier = Modifier.weight(1f), maxLines = 1,
                )
                T("复制", 14, Palette.Ink, FontWeight.SemiBold, Modifier.pressable(onClick = onCopy).padding(8.dp))
                T("重新生成", 14, Palette.Ink, FontWeight.SemiBold, Modifier.pressable(onClick = onGenerate).padding(8.dp))
            }
        }
        !llmReady -> Column(card) {
            T("还没配大模型", 16, Palette.Ink, FontWeight.SemiBold)
            Pill("去配置", Modifier.padding(top = 12.dp).fillMaxWidth(), height = 46.dp, textSize = 15, onClick = onSetup)
        }
        rec.sumState == com.xiaodeng.tinghui.SumState.Done -> Column(card) {
            T("没什么可整理的", 15, Palette.Sub)
        }
        else -> Column(card) {
            Pill("生成纪要", Modifier.fillMaxWidth(), height = 46.dp, textSize = 15, onClick = onGenerate)
        }
    }
}
