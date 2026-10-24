package com.xiaodeng.tinghui.ui

import com.xiaodeng.tinghui.BuildConfig

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.Ask
import com.xiaodeng.tinghui.Prefs
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.Rec
import com.xiaodeng.tinghui.Status
import com.xiaodeng.tinghui.Store
import com.xiaodeng.tinghui.Voice
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 首页的「问 AI」页签：就是一个对话页。右上角新对话、历史对话；
 * 新对话默认问最近一场会，输入框上面可以加减
 */
@Composable
fun AskTab(
    chatId: String,
    initRecs: List<String>?,
    bottomPad: Dp,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenRec: (String) -> Unit,
    onOpenAt: ((String, Long) -> Unit)? = null,
) {
    val recs by Store.recs.collectAsState()
    var history by remember { mutableStateOf(false) }
    val latest = recs.firstOrNull { it.status == Status.Done && it.sentenceCount > 0 }?.id
    ChatPane(chatId, initRecs ?: listOfNotNull(latest), bottomPad, onOpenRec, onOpenAt) {
        XiaoWaiLi(40.dp, wobble = false)
        T(BuildConfig.ASSISTANT, 28, weight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp).weight(1f))
        RoundIcon(R.drawable.ic_history) { history = true }
        Spacer(Modifier.width(10.dp))
        RoundIcon(R.drawable.ic_new_chat, onClick = onNew)
    }
    HistorySheet(history, onDismiss = { history = false }, onOpen = {
        history = false
        onOpen(it)
    })
}

/** 历史对话：点开接着问，长按删 */
@Composable
private fun HistorySheet(visible: Boolean, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val chats by Ask.chats.collectAsState()
    val recs by Store.recs.collectAsState()
    var del by remember(visible) { mutableStateOf<String?>(null) }
    Sheet(visible, onDismiss) {
        T("历史对话", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 12.dp))
        if (chats.isEmpty()) T("还没问过", 14, Palette.Sub, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp))
        chats.forEach { c ->
            val titles = c.recIds.mapNotNull { id -> recs.firstOrNull { it.id == id }?.title }
            if (del == c.id) Row(
                Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.AccentSoft)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("删掉这段对话？", 14, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
                T("算了", 14, Palette.Sub, FontWeight.SemiBold, Modifier.pressable { del = null }.padding(8.dp))
                T("删掉", 14, Palette.Rec, FontWeight.SemiBold, Modifier.pressable {
                    Ask.delete(c.id)
                    del = null
                }.padding(8.dp))
            } else Column(
                Modifier
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .pressable(scaleTo = 0.98f, onLongClick = { del = c.id }) { onOpen(c.id) }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.Card)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                T(c.question, 15, Palette.Ink, FontWeight.SemiBold, maxLines = 1)
                T(
                    SimpleDateFormat("M月d日", Locale.CHINA).format(c.updatedAt) + " · " +
                        titles.joinToString("、").ifBlank { "会议已删除" },
                    12, Palette.Sub, modifier = Modifier.padding(top = 2.dp), maxLines = 1,
                )
            }
        }
    }
}

/** 选会议：按时间列出来，点一下勾上 */
@Composable
fun PickSheet(visible: Boolean, recs: List<Rec>, sel: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    var cur by remember(visible) { mutableStateOf(sel.toSet()) }
    val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
    Sheet(visible, onDismiss) {
        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            T("选会议", 22, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill(if (cur.isEmpty()) "选好了" else "选好了（${cur.size}）", height = 42.dp, textSize = 14, enabled = cur.isNotEmpty()) {
                onDone(recs.filter { it.id in cur }.map { it.id })
            }
        }
        if (recs.isEmpty()) T("还没有转好的会", 14, Palette.Sub, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp))
        Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Card)) {
            recs.forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(Palette.Faint))
                val on = r.id in cur
                Row(
                    Modifier.fillMaxWidth().pressable(scaleTo = 0.98f) { cur = if (on) cur - r.id else cur + r.id }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(if (on) Palette.Accent else Palette.Card)
                            .border(2.dp, if (on) Palette.Accent else Palette.Line, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Ico(R.drawable.ic_check, 14.dp, Color.White) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        T(r.title, 15, Palette.Ink, FontWeight.SemiBold, maxLines = 1)
                        T(
                            fmt.format(r.startedAt) + if (r.brief.isNotBlank()) " · ${r.brief}" else "",
                            12, Palette.Sub, modifier = Modifier.padding(top = 2.dp), maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
