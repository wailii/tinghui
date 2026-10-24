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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.FeishuSync
import com.xiaodeng.tinghui.Outbound
import com.xiaodeng.tinghui.Prefs
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.Rec
import com.xiaodeng.tinghui.Store
import com.xiaodeng.tinghui.Tip
import com.xiaodeng.tinghui.Todo
import com.xiaodeng.tinghui.clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Locale

private class Item(val rec: Rec, val todo: Todo)

/**
 * 待办：所有会上拆出来的待办。「我的」有截止时间的放上面按时间排，没说时间的按会议分组（能收起）；
 * 「全部」按人分组。▶ 时间点回会上那一刻听原话
 */
@Composable
fun TodoScreen(bottomPad: Dp, onOpenAt: (String, Long) -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val recs by Store.recs.collectAsState()
    val feishu by Prefs.feishu.collectAsState()
    var all by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }
    var nudge by remember { mutableStateOf<Item?>(null) }
    // 展开的会；null = 只展开最近一场
    var expanded by remember { mutableStateOf<Set<String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val items = recs.flatMap { r -> r.todos.map { Item(r, it) } }
    val mine = items.filter { it.todo.mine }

    // 打开时把飞书里勾掉的同步回来
    LaunchedEffect(feishu.ready) {
        if (feishu.ready) withContext(Dispatchers.IO) { runCatching { FeishuSync.pullTaskStatus() } }
    }

    fun addTasks(list: List<Item>) {
        if (busy != null || list.isEmpty()) return
        busy = "加到飞书任务"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { list.groupBy { it.rec.id }.entries.sumOf { (id, l) -> FeishuSync.createTasks(id, l.map { it.todo.key }) } }
            }
            busy = null
            r.onSuccess {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                Tip.show("加了 $it 条到飞书任务")
            }.onFailure { Tip.show("没加上：${it.message}") }
        }
    }

    fun toggle(i: Item) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val done = !i.todo.done
        Store.update(i.rec.id) { cur -> cur.copy(todos = cur.todos.map { if (it.key == i.todo.key) it.copy(done = done) else it }) }
        scope.launch(Dispatchers.IO) { runCatching { FeishuSync.setDone(i.rec.id, i.todo.key, done) } }
    }

    fun open(url: String) {
        if (url.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun bucket(t: Todo): Int = when {
        t.due <= 0 -> 5
        dayOf(t.due).isBefore(today) -> 0
        dayOf(t.due) == today -> 1
        dayOf(t.due) == today.plusDays(1) -> 2
        dayOf(t.due).isBefore(today.plusDays(7)) -> 3
        else -> 4
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottomPad + 30.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("待办", 28, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Faint).padding(3.dp)) {
                        listOf(false to "我的 ${mine.count { !it.todo.done }}", true to "全部 ${items.count { !it.todo.done }}").forEach { (v, label) ->
                            val on = all == v
                            T(
                                label, 14, if (on) Palette.Ink else Palette.Sub, if (on) FontWeight.Bold else FontWeight.Medium,
                                Modifier.pressable { all = v }.clip(RoundedCornerShape(11.dp))
                                    .background(if (on) Palette.Card else Color.Transparent).padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
                if (items.isEmpty()) T(
                    "还没有待办", 15, Palette.Sub, align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                )
            }

            if (!all) {
                @Composable
                fun Trailing(i: Item) {
                    if (i.todo.task.isNotBlank()) FeishuMark { open(i.todo.taskUrl) }
                    else if (feishu.ready) SmallButton("+ 任务", enabled = busy == null) { addTasks(listOf(i)) }
                }
                val openMine = mine.filter { !it.todo.done }
                val dated = openMine.filter { it.todo.due > 0 }.sortedBy { it.todo.due }
                if (dated.isNotEmpty()) {
                    item(key = "dated") { GroupLabel("有截止时间 ${dated.size}", Palette.Sub) }
                    item(key = "datedList") {
                        TodoCard(dated) { i ->
                            TodoRow(i, overdue = bucket(i.todo) == 0, onToggle = { toggle(i) }, onOpen = { onOpenAt(i.rec.id, i.todo.at) }) { Trailing(i) }
                        }
                    }
                }
                val byMeeting = openMine.filter { it.todo.due <= 0 }.groupBy { it.rec.id }.values.sortedByDescending { it.first().rec.startedAt }
                if (byMeeting.isNotEmpty()) {
                    item(key = "undated") { GroupLabel(if (dated.isEmpty()) "按会议" else "没说时间的，按会议", Palette.Sub) }
                    byMeeting.forEachIndexed { k, l ->
                        val r = l.first().rec
                        val isOpen = expanded?.contains(r.id) ?: (k == 0)
                        item(key = "m${r.id}") {
                            MeetingGroup(r, l.size, isOpen, onToggle = {
                                val cur = expanded ?: setOfNotNull(byMeeting.first().first().rec.id)
                                expanded = if (r.id in cur) cur - r.id else cur + r.id
                            }) {
                                l.forEach { i ->
                                    TodoRow(i, overdue = false, showMeeting = false, onToggle = { toggle(i) }, onOpen = { onOpenAt(i.rec.id, i.todo.at) }) { Trailing(i) }
                                }
                            }
                        }
                    }
                }
                val done = mine.filter { it.todo.done }
                if (done.isNotEmpty()) {
                    item(key = "done") {
                        T(
                            (if (showDone) "收起" else "做完的 ${done.size}"), 13, Palette.Sub, FontWeight.SemiBold,
                            Modifier.padding(top = 8.dp, start = 6.dp).pressable { showDone = !showDone }.padding(vertical = 4.dp),
                        )
                    }
                    if (showDone) item(key = "doneList") {
                        TodoCard(done) { i -> TodoRow(i, overdue = false, onToggle = { toggle(i) }, onOpen = { onOpenAt(i.rec.id, i.todo.at) }) {} }
                    }
                }
            } else {
                val byOwner = items.filter { !it.todo.done }.groupBy { if (it.todo.mine) "你" else it.todo.owner.ifBlank { "没说谁" } }
                    .toList().sortedBy { if (it.first == "你") "" else it.first }
                byOwner.forEach { (owner, l) ->
                    item(key = "o$owner") { GroupLabel("$owner · ${l.size}", Palette.Sub) }
                    item(key = "oc$owner") {
                        TodoCard(l) { i ->
                            TodoRow(i, overdue = i.todo.due in 1 until System.currentTimeMillis(), onToggle = { toggle(i) }, onOpen = { onOpenAt(i.rec.id, i.todo.at) }) {
                                // 没认出名字的「说话人 3」不知道该催谁
                                if (!i.todo.mine && owner != "没说谁" && !Regex("^说话人\\s*\\d+$").matches(owner)) SmallButton("催一下") { nudge = i }
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Palette.Bg, Palette.Bg.copy(alpha = 0f))))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
        )
        NudgeSheet(nudge) { nudge = null }
    }
}

@Composable
private fun GroupLabel(text: String, color: Color) {
    T(text, 13, color, FontWeight.Bold, Modifier.padding(start = 6.dp, top = 8.dp))
}

/** 一场会的待办：会名、日期、几条；点会名收起或展开 */
@Composable
private fun MeetingGroup(r: Rec, n: Int, open: Boolean, onToggle: () -> Unit, rows: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Card)) {
        Row(
            Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, onClick = onToggle).padding(start = 16.dp, end = 14.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico(R.drawable.ic_down, 16.dp, Palette.Ink, Modifier.graphicsLayer { rotationZ = if (open) 0f else -90f })
            Spacer(Modifier.width(8.dp))
            T(r.title, 15, Palette.Ink, FontWeight.Bold, Modifier.weight(1f), maxLines = 1)
            T(SimpleDateFormat("M/d", Locale.CHINA).format(r.startedAt) + " · $n 条", 12, Palette.Sub)
        }
        if (open) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Faint))
            rows()
        }
    }
}

@Composable
private fun TodoCard(list: List<Item>, row: @Composable (Item) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Card)) {
        list.forEachIndexed { k, i ->
            if (k > 0) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(Palette.Faint))
            row(i)
        }
    }
}

@Composable
private fun TodoRow(i: Item, overdue: Boolean, showMeeting: Boolean = true, onToggle: () -> Unit, onOpen: () -> Unit, trailing: @Composable () -> Unit) {
    val t = i.todo
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 13.dp, bottom = 13.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 1.dp).size(24.dp).pressable(scaleTo = 0.85f, onClick = onToggle).clip(CircleShape)
                .background(if (t.done) Palette.Ink else Palette.Card).border(2.dp, if (t.done) Palette.Ink else Palette.Hint, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (t.done) Ico(R.drawable.ic_check, 15.dp, Color.White) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(t.text, 15, if (t.done) Palette.Sub else Palette.Ink, lineHeight = 22)
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                dueLabel(t)?.let { (txt, hot) ->
                    T(
                        txt, 12, if (hot && overdue) Palette.AccentText else Palette.Sub, if (hot && overdue) FontWeight.Bold else FontWeight.Medium,
                        if (hot && overdue) Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.AccentSoft).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Row(Modifier.weight(1f, fill = false).pressable(enabled = t.at >= 0, onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                    if (t.at >= 0) T("▶ ${clock(t.at)} ", 12, Palette.AccentText, FontWeight.Bold, tabular = true)
                    if (showMeeting) T(i.rec.title, 12, Palette.Sub, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** 截止：逾期几天、今天几点、周几、几月几号；会上原话也显示 */
private fun dueLabel(t: Todo): Pair<String, Boolean>? {
    if (t.due <= 0) return null
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val d = Instant.ofEpochMilli(t.due).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(d, today)
    val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(t.due)
    return when {
        days > 0 -> "逾期 $days 天" to true
        days == 0L -> "今天 $time" to false
        days == -1L -> "明天 $time" to false
        days > -7 -> "周" + "一二三四五六日"[d.dayOfWeek.value - 1] to false
        else -> "${d.monthValue}/${d.dayOfMonth}" to false
    }
}

@Composable
private fun FeishuMark(onClick: () -> Unit) {
    Row(Modifier.pressable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Ico(R.drawable.ic_check, 14.dp, Palette.Good)
        T("飞书", 12, Palette.Good, FontWeight.Bold)
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    T(
        text, 12, if (enabled) Palette.Ink else Palette.Hint, FontWeight.Bold,
        Modifier.pressable(enabled = enabled, onClick = onClick).clip(RoundedCornerShape(50))
            .border(1.dp, Palette.Line, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** 催一下：照你说话的样子写一句，复制了去飞书发 */
@Composable
private fun NudgeSheet(item: Item?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val last = remember { mutableStateOf<Item?>(null) }
    if (item != null) last.value = item
    val i = item ?: last.value
    var text by remember(item) { mutableStateOf("") }
    var writing by remember(item) { mutableStateOf(false) }
    fun draft(style: String = "") {
        val it0 = i ?: return
        writing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Outbound.nudge(it0.rec, it0.todo, style) } }
            writing = false
            text = r.getOrElse { "（没写出来：${it.message}）" }
        }
    }
    LaunchedEffect(item) { if (item != null) draft() }
    Sheet(item != null, onDismiss) {
        val owner = i?.todo?.owner ?: ""
        T("催一下$owner", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        Box(Modifier.padding(top = 12.dp)) {
            if (writing && text.isBlank()) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Card).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BusyDots()
                T("照你说话的样子写", 14, Palette.Sub)
            }
            else Field(text, { text = it }, "", size = 15, singleLine = false, minHeight = 80)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("客气点" to "polite", "再短点" to "short", "带上原话" to "quote").forEach { (label, style) ->
                T(
                    label, 13, if (writing) Palette.Hint else Palette.Ink, FontWeight.Medium,
                    Modifier.pressable(enabled = !writing) { draft(style) }.clip(RoundedCornerShape(50))
                        .border(1.dp, Palette.Line, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Pill("复制，去飞书发", Modifier.padding(top = 16.dp).fillMaxWidth(), enabled = text.isNotBlank() && !writing) {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("催一下", text))
            ctx.packageManager.getLaunchIntentForPackage("com.ss.android.lark")?.let { ctx.startActivity(it) } ?: Tip.show("已复制")
            onDismiss()
        }
        Spacer(Modifier.height(4.dp))
    }
}
