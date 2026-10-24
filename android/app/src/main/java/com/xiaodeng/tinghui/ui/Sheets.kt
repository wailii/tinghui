package com.xiaodeng.tinghui.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaodeng.tinghui.Dashscope
import com.xiaodeng.tinghui.Feishu
import com.xiaodeng.tinghui.Prefs
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.Rec
import com.xiaodeng.tinghui.Speaker
import com.xiaodeng.tinghui.Store
import com.xiaodeng.tinghui.Tip
import com.xiaodeng.tinghui.Voice
import com.xiaodeng.tinghui.clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val BAILIAN_KEY_URL = "https://bailian.console.aliyun.com/?tab=model#/api-key"

fun feishuConsoleUrl(appId: String) = "https://open.feishu.cn/app/$appId/baseinfo"

// ---------- 通用小件 ----------

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    size: Int = 16,
    singleLine: Boolean = true,
    focus: FocusRequester? = null,
    onDone: (() -> Unit)? = null,
    minHeight: Int = 0,
    bg: Color = Palette.Card,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = size.sp, color = Palette.Ink, lineHeight = (size * 1.6f).sp),
            cursorBrush = SolidColor(Palette.Accent),
            keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight.dp)
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
            decorationBox = { inner ->
                if (value.isEmpty()) T(hint, size, Palette.Hint)
                inner()
            },
        )
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (on) 20.dp else 2.dp, spring(0.7f, 600f), label = "tg")
    val bg by animateColorAsState(if (on) Palette.Ink else Palette.Line, label = "tgc")
    Box(
        Modifier
            .size(46.dp, 28.dp)
            .pressable(scaleTo = 0.94f) { onChange(!on) }
            .clip(RoundedCornerShape(50))
            .background(bg)
    ) {
        Box(Modifier.offset(x = x, y = 2.dp).size(24.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun MenuRow(icon: Int, text: String, color: Color = Palette.Ink, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.98f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(icon, 20.dp, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            T(text, 16, color, FontWeight.Medium)
            if (sub != null) T(sub, 12, Palette.Sub, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.Card)
    ) { content() }
}

@Composable
fun Divider() = Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(Palette.Faint))

@Composable
fun LinkText(text: String, url: String) {
    val ctx = LocalContext.current
    T(
        "$text ↗", 14, Palette.Accent, FontWeight.SemiBold,
        Modifier
            .pressable {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    )
}

// ---------- 起名 ----------

/** 改说话人名字：输入就保存；认得的人点一下就是；猜的放第一个 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NameSheet(
    rec: Rec,
    sp: Speaker?,
    visible: Boolean,
    player: PlayerState,
    clip: (Speaker) -> com.xiaodeng.tinghui.Sentence?,
    onDismiss: () -> Unit,
) {
    val people by Voice.people.collectAsState()
    var text by remember(sp?.idx, visible) { mutableStateOf("") }
    var merge by remember(sp?.idx, visible) { mutableStateOf<Voice.Person?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(visible, sp?.idx) {
        if (visible) {
            delay(300)
            runCatching { focus.requestFocus() }
        }
    }
    // 关面板的动画期间 sp 已经变 null，留着最后一个，免得内容先消失
    val last = remember { mutableStateOf<Speaker?>(null) }
    if (sp != null) last.value = sp
    val spk = sp ?: last.value
    Sheet(visible, onDismiss) {
        val sp = spk ?: return@Sheet
        val current = if (Store.known(sp)) Voice.name(sp.personId!!) else null
        val guessId = if (current == null) sp.guessId?.takeIf { Voice.name(it) != null } else null
        fun pick(personId: String?, name: String?) {
            Store.nameSpeaker(rec.id, sp.idx, personId, name)
            Tip.show("已改成${personId?.let { Voice.name(it) } ?: name?.trim()}")
            onDismiss()
        }
        fun choose(p: Voice.Person) {
            if (rec.speakers.any { it.idx != sp.idx && it.personId == p.id }) merge = p else pick(p.id, null)
        }
        // 输入的名字已经在库里：当成选这个人（同场已有就先问要不要合并）
        fun save() {
            val n = text.trim()
            if (n.isEmpty()) return
            people.firstOrNull { it.name == n }?.let { choose(it) } ?: pick(null, n)
        }

        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(sp, 40)
            Spacer(Modifier.width(12.dp))
            Field(
                text, { text = it.take(12) }, current ?: Store.speakerName(sp), Modifier.weight(1f),
                size = 17, focus = focus, onDone = { save() },
            )
            Spacer(Modifier.width(10.dp))
            Pill("保存", enabled = text.isNotBlank(), height = 50.dp, textSize = 15) { save() }
        }

        merge?.let { p ->
            Row(
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.AccentSoft)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("这场已有${p.name}，合成一个？", 14, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
                T("取消", 14, Palette.Sub, FontWeight.SemiBold, Modifier.pressable { merge = null }.padding(8.dp))
                T("合并", 14, Palette.Accent, FontWeight.SemiBold, Modifier.pressable { pick(p.id, null) }.padding(8.dp))
            }
        }

        val ranked = people
            .filter { it.id != sp.personId }
            .sortedByDescending { p -> if (p.id == guessId) 2f else sp.emb?.let { Voice.similarity(it, p) } ?: -1f }
        if (ranked.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ranked.take(12).forEach { p ->
                    val hot = p.id == guessId
                    T(
                        if (hot) "像${p.name}" else p.name, 15,
                        if (hot) Color.White else Palette.Ink, FontWeight.SemiBold,
                        Modifier
                            .pressable { choose(p) }
                            .clip(RoundedCornerShape(50))
                            .background(if (hot) Palette.Accent else Palette.Card)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }

        Row(Modifier.padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val playing = player.clipEnd >= 0
            Row(
                Modifier
                    .pressable {
                        if (playing) player.stopClip()
                        else clip(sp)?.let { c -> player.playClip(c.begin, minOf(c.end, c.begin + 12_000)) }
                    }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(if (playing) R.drawable.ic_stop else R.drawable.ic_play, 16.dp, Palette.Ink)
                T(if (playing) " 停" else " 听一句", 14, Palette.Ink, FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            if (current != null) {
                T(
                    "不是$current", 14, Palette.Sub, FontWeight.Medium,
                    Modifier.pressable { Store.clearSpeaker(rec.id, sp.idx) }.padding(6.dp),
                )
            }
        }
    }
}

// ---------- 改标题 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenameSheet(visible: Boolean, rec: Rec, onDismiss: () -> Unit) {
    var text by remember(visible) { mutableStateOf(rec.title) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) {
            delay(300)
            runCatching { focus.requestFocus() }
        }
    }
    fun save() {
        if (text.isNotBlank()) Store.update(rec.id) { it.copy(title = text.trim(), titleManual = true) }
        onDismiss()
    }
    val presets = listOf("周会", "需求评审", "站会", "访谈", "复盘", "1on1")
    Sheet(visible, onDismiss) {
        T("会议名", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        Field(text, { text = it.take(40) }, "比如：需求评审", Modifier.padding(top = 14.dp), size = 18, focus = focus, onDone = { save() })
        FlowRow(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { p ->
                T(
                    p, 14, Palette.Ink, FontWeight.Medium,
                    Modifier
                        .pressable { text = p }
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Faint)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Pill("好", Modifier.padding(top = 18.dp).fillMaxWidth(), enabled = text.isNotBlank()) { save() }
    }
}

// ---------- 飞书 ----------

/** 飞书应用要开的东西，和用到的接口一一对应 */
private val FEISHU_NEEDS = listOf(
    "应用能力 → 开启「机器人」",
    "权限：以应用的身份发消息（im:message:send_as_bot）",
    "权限：上传图片或文件资源（im:resource）",
    "权限：管理应用自身资源（application:application:self_manage）",
    "可用范围里要有你自己；改完记得发布一个版本",
)

/**
 * 接飞书：只填 App ID 和 App Secret（用你自己建的飞书应用），收件人固定是你本人。
 * 先验证，通过才保存；从详情页打开的，保存后直接把这场发出去。
 */
@Composable
fun FeishuSheet(visible: Boolean, onDismiss: () -> Unit, sendAfter: Boolean = false, onReady: () -> Unit = {}) {
    val cur by Prefs.feishu.collectAsState()
    var appId by remember(visible) { mutableStateOf(cur.appId) }
    var secret by remember(visible) { mutableStateOf(cur.secret) }
    var audio by remember(visible) { mutableStateOf(cur.withAudio) }
    var reveal by remember(visible) { mutableStateOf(false) }
    var needs by remember(visible) { mutableStateOf(false) }
    var busy by remember(visible) { mutableStateOf(false) }
    var result by remember(visible) { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    Sheet(visible, onDismiss) {
        T(if (cur.ready) "飞书" else "接上飞书", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        if (cur.ready) T("已接上「${cur.botName.ifBlank { "飞书机器人" }}」", 14, Palette.Good, FontWeight.Medium, Modifier.padding(top = 4.dp))

        Column(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.Card)
                .pressable(scaleTo = 0.99f) { needs = !needs }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("需要开的权限", 15, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
                T(if (needs) "收起" else "展开", 13, Palette.Sub)
            }
            if (needs) {
                FEISHU_NEEDS.forEachIndexed { i, line ->
                    T("${i + 1}. $line", 13, Palette.Sub, lineHeight = 19, modifier = Modifier.padding(top = 6.dp))
                }
                LinkText("打开飞书开发者后台", if (appId.isNotBlank()) feishuConsoleUrl(appId) else "https://open.feishu.cn/app")
            }
        }

        Label("App ID", Modifier.padding(top = 14.dp, start = 4.dp))
        Field(appId, { appId = it.trim(); result = null }, "cli_…", Modifier.padding(top = 6.dp), size = 15)
        Label("App Secret", Modifier.padding(top = 12.dp, start = 4.dp))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.Card)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                BasicTextField(
                    value = secret,
                    onValueChange = { secret = it.trim(); result = null },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = Palette.Ink),
                    cursorBrush = SolidColor(Palette.Accent),
                    visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (secret.isEmpty()) T("App Secret", 15, Palette.Hint)
                        inner()
                    },
                )
            }
            T(
                if (reveal) "隐藏" else "显示", 14, Palette.Sub, FontWeight.SemiBold,
                Modifier.pressable { reveal = !reveal }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            )
        }

        Row(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.Card)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("同时发录音文件", 15, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
            Toggle(audio) { audio = it }
        }

        result?.let { (ok, msg) ->
            T(msg, 13, if (ok) Palette.Good else Palette.Accent, FontWeight.Medium, Modifier.padding(top = 12.dp, start = 4.dp), lineHeight = 19)
            if (!ok && "权限" in msg && appId.isNotBlank()) {
                LinkText("打开这个应用的权限管理页", "https://open.feishu.cn/app/$appId/auth")
            }
        }
        Pill(
            when {
                busy -> "正在验证…"
                sendAfter -> "验证并发送"
                else -> "验证并保存"
            },
            Modifier.padding(top = 14.dp).fillMaxWidth(),
            enabled = !busy && appId.isNotBlank() && secret.isNotBlank(),
        ) {
            busy = true
            result = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Feishu.verify(appId, secret) } }
                busy = false
                if (r.isSuccess) {
                    val c = r.getOrThrow()
                    val bot = c.botName
                    Prefs.setFeishu(cur.copy(appId = appId, secret = secret, withAudio = audio, botName = bot, openId = c.openId))
                    result = true to "已接上「${bot.ifBlank { "飞书机器人" }}」"
                    delay(600)
                    onReady()
                } else {
                    result = false to (r.exceptionOrNull()?.message ?: "没接上")
                }
            }
        }
        if (cur.ready && !sendAfter) {
            T(
                "发一条测试消息", 14, Palette.Ink, FontWeight.SemiBold,
                Modifier.padding(top = 14.dp, start = 4.dp).pressable {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Feishu.sendTest() } }
                        result = if (r.isSuccess) true to "发了，去飞书看看" else false to (r.exceptionOrNull()?.message ?: "没发出去")
                    }
                },
            )
        }
    }
}

// ---------- 阿里百炼 Key ----------

@Composable
fun KeySheet(visible: Boolean, onDismiss: () -> Unit) {
    val cur by Prefs.apiKey.collectAsState()
    var text by remember(visible) { mutableStateOf("") }
    var checking by remember(visible) { mutableStateOf(false) }
    var result by remember(visible) { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    Sheet(visible, onDismiss) {
        T("阿里百炼 API Key", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        T("现在用的：${maskKey(cur)}", 14, Palette.Sub, modifier = Modifier.padding(top = 4.dp), tabular = true)
        LinkText("去百炼控制台拿 Key", BAILIAN_KEY_URL)
        Field(text, { text = it.trim() }, "粘贴新的 Key，sk-…", Modifier.padding(top = 8.dp), size = 15)
        result?.let { (ok, msg) ->
            T(msg, 13, if (ok) Palette.Good else Palette.Accent, FontWeight.Medium, Modifier.padding(top = 12.dp, start = 4.dp))
        }
        Pill(
            if (checking) "正在试…" else "试一下，能用就保存",
            Modifier.padding(top = 16.dp).fillMaxWidth(),
            enabled = !checking && text.isNotBlank(),
        ) {
            checking = true
            result = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Dashscope.check(text) } }
                checking = false
                if (r.isSuccess) {
                    Prefs.setApiKey(text)
                    result = true to "能用，已保存"
                    delay(700)
                    onDismiss()
                } else result = false to (r.exceptionOrNull()?.message ?: "不能用")
            }
        }
        if (com.xiaodeng.tinghui.BuildConfig.DASHSCOPE_KEY.isNotBlank() && cur != com.xiaodeng.tinghui.BuildConfig.DASHSCOPE_KEY) {
            T(
                "恢复默认", 14, Palette.Sub, FontWeight.Medium,
                Modifier.padding(top = 14.dp, start = 4.dp).pressable {
                    Prefs.setApiKey("")
                    onDismiss()
                },
            )
        }
    }
}

fun maskKey(k: String) = if (k.length < 12) (if (k.isBlank()) "没填" else "已填") else k.take(6) + "••••" + k.takeLast(4)

// ---------- 热词 ----------

@Composable
fun HotwordSheet(visible: Boolean, onDismiss: () -> Unit) {
    val hot by Prefs.hotwords.collectAsState()
    var text by remember(visible) { mutableStateOf(hot) }
    Sheet(visible, onDismiss) {
        T("热词", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        Field(text, { text = it }, "一行一个", Modifier.padding(top = 14.dp), singleLine = false, minHeight = 200)
        val bad = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !Prefs.validHotword(it) }
        val names = Voice.people.collectAsState().value.map { it.name }
        T(
            if (bad.isEmpty()) "${text.lines().count { it.isNotBlank() }} 个" else "太长（中文最多 15 字）：${bad.joinToString("、")}",
            13, if (bad.isEmpty()) Palette.Sub else Palette.Accent, modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
        if (names.isNotEmpty()) {
            T("另含人名：${names.joinToString("、")}", 13, Palette.Sub, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
        Pill("保存", Modifier.padding(top = 14.dp).fillMaxWidth()) {
            Prefs.setHotwords(text)
            onDismiss()
        }
    }
}

// ---------- 第一次录音前 ----------

@Composable
fun FirstRecordSheet(visible: Boolean, onBattery: () -> Unit, onStart: () -> Unit, onDismiss: () -> Unit) {
    Sheet(visible, onDismiss) {
        T("录长会前", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        Group {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("允许后台耗电", 16, Palette.Ink, FontWeight.SemiBold)
                    T("不然锁屏后可能被停掉", 13, Palette.Sub, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.width(12.dp))
                Pill("去设置", dark = false, height = 40.dp, textSize = 14, onClick = onBattery)
            }
        }
        Pill("开始录音", Modifier.padding(top = 18.dp).fillMaxWidth(), height = 56.dp, onClick = onStart)
    }
}

// ---------- 大模型 ----------

/** 配出纪要用的大模型：OpenAI 兼容接口，地址、Key、模型名自己填；能拉到模型列表就给你点选 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LlmSheet(visible: Boolean, onDismiss: () -> Unit, onReady: () -> Unit = {}) {
    val cur by Prefs.llm.collectAsState()
    var url by remember(visible) { mutableStateOf(cur.url) }
    var key by remember(visible) { mutableStateOf(cur.key) }
    var model by remember(visible) { mutableStateOf(cur.model) }
    var auto by remember(visible) { mutableStateOf(cur.auto) }
    var noThink by remember(visible) { mutableStateOf(cur.noThink) }
    var reveal by remember(visible) { mutableStateOf(false) }
    var busy by remember(visible) { mutableStateOf(false) }
    var loading by remember(visible) { mutableStateOf(false) }
    var models by remember(visible) { mutableStateOf<List<String>?>(null) }
    var result by remember(visible) { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    Sheet(visible, onDismiss) {
        T("纪要用的大模型", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        T("OpenAI 兼容接口", 14, Palette.Sub, modifier = Modifier.padding(top = 4.dp))
        Label("接口地址", Modifier.padding(top = 16.dp, start = 4.dp))
        Field(url, { url = it.trim(); result = null; models = null }, "https://…/v1", Modifier.padding(top = 6.dp), size = 15)
        Label("API Key", Modifier.padding(top = 12.dp, start = 4.dp))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.Card)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                BasicTextField(
                    value = key,
                    onValueChange = { key = it.trim(); result = null },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = Palette.Ink),
                    cursorBrush = SolidColor(Palette.Accent),
                    visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (key.isEmpty()) T("可不填", 15, Palette.Hint)
                        inner()
                    },
                )
            }
            T(
                if (reveal) "隐藏" else "显示", 14, Palette.Sub, FontWeight.SemiBold,
                Modifier.pressable { reveal = !reveal }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        Label("模型名", Modifier.padding(top = 12.dp, start = 4.dp))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(model, { model = it.trim(); result = null }, "比如 deepseek-v4.1-flash", Modifier.weight(1f), size = 15)
            Spacer(Modifier.width(10.dp))
            Pill(
                if (loading) "拉取中" else "拉列表", dark = false, height = 48.dp, textSize = 14,
                enabled = url.isNotBlank() && !loading,
            ) {
                loading = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { com.xiaodeng.tinghui.Llm.models(url, key) } }
                    loading = false
                    models = r.getOrNull()
                    if (r.isFailure) result = false to "拉不到列表，手动填：${r.exceptionOrNull()?.message ?: ""}"
                    else if (models.isNullOrEmpty()) result = false to "接口没给列表，手动填"
                }
            }
        }
        val shown = models?.filter { model.isBlank() || it.contains(model, true) }?.take(40).orEmpty()
        if (shown.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shown.forEach { m ->
                    T(
                        m, 13, if (m == model) Color.White else Palette.Ink, FontWeight.Medium,
                        Modifier
                            .pressable { model = m; result = null }
                            .clip(RoundedCornerShape(50))
                            .background(if (m == model) Palette.Ink else Palette.Card)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }

        Row(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.Card)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("录完自动出纪要", 15, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
            Toggle(auto) { auto = it }
        }
        Row(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.Card)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                T("关闭思考", 15, Palette.Ink, FontWeight.Medium)
                T("更快；模型不支持会自动忽略", 12, Palette.Sub)
            }
            Toggle(noThink) { noThink = it }
        }

        result?.let { (ok, msg) ->
            T(msg, 13, if (ok) Palette.Good else Palette.Accent, FontWeight.Medium, Modifier.padding(top = 12.dp, start = 4.dp), lineHeight = 19)
        }
        Pill(
            if (busy) "正在试…" else "试一下并保存",
            Modifier.padding(top = 14.dp).fillMaxWidth(),
            enabled = !busy && url.isNotBlank() && model.isNotBlank(),
        ) {
            busy = true
            result = null
            val cfg = Prefs.Llm(url, key, model, auto, noThink)
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { com.xiaodeng.tinghui.Llm.test(cfg) } }
                busy = false
                if (r.isSuccess) {
                    Prefs.setLlm(cfg)
                    result = true to "能用，已保存"
                    delay(800)
                    onReady()
                } else {
                    result = false to (r.exceptionOrNull()?.message ?: "没调通")
                }
            }
        }
    }
}

// ---------- 分类（文件夹） ----------

/** 给一场会换分类：选已有的、新建一个、或者不分类。手动选过的，大模型重新生成纪要时不会再改 */
@Composable
fun FolderPickSheet(visible: Boolean, rec: Rec, onDismiss: () -> Unit) {
    val folders by Prefs.folders.collectAsState()
    val recs by Store.recs.collectAsState()
    var text by remember(visible) { mutableStateOf("") }
    fun pick(name: String?) {
        Store.update(rec.id) { it.copy(folder = name, folderManual = true) }
        Tip.show(if (name == null) "移出分类了" else "放进「$name」了")
        onDismiss()
    }
    Sheet(visible, onDismiss) {
        T("放到哪个分类", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        Group {
            folders.forEachIndexed { i, f ->
                if (i > 0) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(Palette.Faint))
                val n = recs.count { it.folder == f.name }
                Row(
                    Modifier.fillMaxWidth().pressable(scaleTo = 0.98f) { pick(f.name) }.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(R.drawable.ic_folder, 20.dp, if (rec.folder == f.name) Palette.Accent else Palette.Sub)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        T(f.name, 16, Palette.Ink, FontWeight.Medium)
                        if (f.note.isNotBlank()) T(f.note, 12, Palette.Sub, maxLines = 1)
                    }
                    T("$n 场", 13, Palette.Sub)
                    if (rec.folder == f.name) {
                        Spacer(Modifier.width(8.dp))
                        Ico(R.drawable.ic_check, 18.dp, Palette.Accent)
                    }
                }
            }
            if (folders.isNotEmpty()) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(Palette.Faint))
            Row(
                Modifier.fillMaxWidth().pressable(scaleTo = 0.98f) { pick(null) }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(R.drawable.ic_close, 20.dp, Palette.Sub)
                Spacer(Modifier.width(14.dp))
                T("不分类", 16, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
                if (rec.folder == null) Ico(R.drawable.ic_check, 18.dp, Palette.Accent)
            }
        }
        Label("新建一个", Modifier.padding(top = 18.dp, start = 4.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(text, { text = it.take(10) }, "比如：资源管理", Modifier.weight(1f), size = 16, onDone = {
                if (text.isNotBlank()) pick(Prefs.ensureFolder(text))
            })
            Spacer(Modifier.width(10.dp))
            Pill("好", enabled = text.isNotBlank(), height = 50.dp, textSize = 15) { pick(Prefs.ensureFolder(text)) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

const val NEW_FOLDER = "\u0000new"

/** 改分类：名字、一句说明（告诉大模型这类会装什么）、删除 */
@Composable
fun FolderEditSheet(name: String?, onDismiss: () -> Unit) {
    val folders by Prefs.folders.collectAsState()
    val recs by Store.recs.collectAsState()
    val isNew = name == NEW_FOLDER
    val f = folders.firstOrNull { it.name == name }
    var text by remember(name) { mutableStateOf(f?.name ?: "") }
    var note by remember(name) { mutableStateOf(f?.note ?: "") }
    var sure by remember(name) { mutableStateOf(false) }
    val n = recs.count { it.folder == name }
    Sheet(name != null && (isNew || f != null), onDismiss) {
        T(if (isNew) "新建分类" else (f?.name ?: ""), 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        if (!isNew) T("里面有 $n 场会", 14, Palette.Sub, modifier = Modifier.padding(top = 4.dp))
        Label("名字", Modifier.padding(top = 16.dp, start = 4.dp))
        Field(text, { text = it.take(10) }, "比如：资源管理", Modifier.padding(top = 6.dp), size = 16)
        Label("说明", Modifier.padding(top = 12.dp, start = 4.dp))
        Field(note, { note = it.take(40) }, "可不填，比如：算力、账单相关", Modifier.padding(top = 6.dp), size = 15)
        val dup = text.isNotBlank() && folders.any { it.name.equals(text.trim(), true) && it.name != f?.name }
        if (dup) T("已经有这个分类了", 13, Palette.Accent, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        Pill("保存", Modifier.padding(top = 16.dp).fillMaxWidth(), enabled = text.isNotBlank() && !dup) {
            val nn = text.trim()
            if (isNew) Prefs.setFolders(folders + Prefs.Folder(nn, note.trim()))
            else {
                if (nn != f!!.name) Store.renameFolder(f.name, nn)
                Prefs.setFolders(Prefs.folders.value.map { if (it.name == nn) it.copy(note = note.trim()) else it })
            }
            onDismiss()
        }
        if (!isNew) {
            Pill(
                if (sure) "确定删掉这个分类" else "删掉这个分类",
                Modifier.padding(top = 10.dp).fillMaxWidth(), dark = false,
                color = if (sure) Palette.Rec else null, height = 50.dp, textSize = 15,
            ) {
                if (!sure) sure = true
                else {
                    Store.removeFolder(f!!.name)
                    onDismiss()
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ---------- 纪要模板 ----------

/** 用哪个模板生成纪要 */
@Composable
fun TemplatePickSheet(visible: Boolean, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val list by Prefs.templates.collectAsState()
    val def by Prefs.defaultTemplate.collectAsState()
    val sel = current.ifBlank { def }
    Sheet(visible, onDismiss) {
        T("用哪个模板", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        Group {
            list.forEachIndexed { i, t ->
                if (i > 0) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Palette.Faint))
                Row(
                    Modifier.fillMaxWidth().pressable(scaleTo = 0.98f) { onPick(t.id) }.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        T(t.name, 16, Palette.Ink, FontWeight.Medium)
                        T(sections(t.body), 12, Palette.Sub, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
                    }
                    if (t.id == def) T("默认", 12, Palette.Sub, modifier = Modifier.padding(horizontal = 8.dp))
                    if (t.id == sel) Ico(R.drawable.ic_check, 18.dp, Palette.Accent)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

const val NEW_TEMPLATE = "\u0000newtpl"

/** 模板分哪几节：「结论 · 待办 · 讨论过程」 */
fun sections(body: String) = body.lines().filter { it.startsWith("## ") }.joinToString(" · ") { it.removePrefix("## ").trim() }

/** 编辑模板：名字、正文怎么写；设为默认；删掉 */
@Composable
fun TemplateSheet(id: String?, onDismiss: () -> Unit) {
    val list by Prefs.templates.collectAsState()
    val def by Prefs.defaultTemplate.collectAsState()
    val isNew = id == NEW_TEMPLATE
    val t = list.firstOrNull { it.id == id }
    var name by remember(id) { mutableStateOf(t?.name ?: "") }
    var body by remember(id) { mutableStateOf(t?.body ?: Prefs.template(def).body) }
    var sure by remember(id) { mutableStateOf(false) }
    val builtin = Prefs.BUILTIN_TEMPLATES.firstOrNull { it.id == t?.id }
    Sheet(id != null && (isNew || t != null), onDismiss) {
        T(if (isNew) "新建模板" else "编辑模板", 22, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        T("写纪要分几节、每节写什么。标题、分类、摘要不用写", 13, Palette.Sub, modifier = Modifier.padding(top = 4.dp))
        Field(name, { name = it.take(12) }, "模板名", Modifier.padding(top = 14.dp), size = 16)
        if (isNew) {
            // 从现成的模板改起
            Row(
                Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                list.forEach { o ->
                    val on = o.body == body
                    T(
                        o.name, 13, if (on) Color.White else Palette.Ink, FontWeight.SemiBold,
                        Modifier
                            .pressable { body = o.body }
                            .clip(RoundedCornerShape(50))
                            .background(if (on) Palette.Ink else Palette.Card)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Field(body, { body = it }, "纪要正文怎么写", Modifier.padding(top = 10.dp), size = 14, singleLine = false, minHeight = 300)
        if (builtin != null && body.trim() != builtin.body.trim()) {
            T(
                "恢复原来的写法", 14, Palette.Sub, FontWeight.Medium,
                Modifier.padding(top = 10.dp, start = 4.dp).pressable { body = builtin.body },
            )
        }
        Pill("保存", Modifier.padding(top = 14.dp).fillMaxWidth(), enabled = name.isNotBlank() && body.isNotBlank()) {
            Prefs.saveTemplate(Prefs.Template(if (isNew) "t" + System.currentTimeMillis() else t!!.id, name.trim(), body.trim()))
            onDismiss()
        }
        if (!isNew && t != null) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(
                    if (t.id == def) "已是默认" else "设为默认", Modifier.weight(1f), dark = false,
                    enabled = t.id != def, height = 46.dp, textSize = 15,
                ) { Prefs.setDefaultTemplate(t.id) }
                Pill(
                    if (sure) "确定删掉" else "删掉", Modifier.weight(1f), dark = false,
                    enabled = list.size > 1, color = if (sure) Palette.Rec else null, height = 46.dp, textSize = 15,
                ) {
                    if (!sure) sure = true
                    else {
                        Prefs.deleteTemplate(t.id)
                        onDismiss()
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
