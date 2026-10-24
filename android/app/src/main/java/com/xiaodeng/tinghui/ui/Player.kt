package com.xiaodeng.tinghui.ui

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaodeng.tinghui.Mark
import com.xiaodeng.tinghui.R
import com.xiaodeng.tinghui.Sentence
import com.xiaodeng.tinghui.clock
import java.io.File

/** 播放器：懒加载，第一次点播放或拖进度条时才打开文件 */
@Stable
class PlayerState(private val file: File, val durationMs: Long) {
    private var mp: MediaPlayer? = null
    var playing by mutableStateOf(false)
        private set
    var pos by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
        private set

    /** 试听一句：放到这里自动停，停了回到原来的位置 */
    var clipEnd by mutableLongStateOf(-1L)
        private set
    private var resumeAt = 0L
    private var resumePlaying = false

    private fun ensure(): MediaPlayer? = mp ?: MediaPlayer().let { p ->
        runCatching {
            p.setDataSource(file.path)
            p.prepare()
            p
        }.onFailure { p.release() }.getOrNull()
    }?.also {
        if (mp == null) {
            it.setOnCompletionListener { _ ->
                playing = false
                pos = durationMs
            }
            mp = it
        }
    }

    fun tick() {
        if (playing) mp?.let { pos = it.currentPosition.toLong() }
        if (clipEnd >= 0 && pos >= clipEnd) stopClip()
    }

    fun playClip(from: Long, to: Long) {
        if (clipEnd < 0) {
            resumeAt = pos
            resumePlaying = playing
        }
        clipEnd = to
        seek(from, true)
    }

    fun cancelClip() {
        clipEnd = -1L
    }

    fun stopClip() {
        if (clipEnd < 0) return
        clipEnd = -1L
        seek(resumeAt, resumePlaying)
    }

    fun seek(ms: Long, play: Boolean? = null) {
        val p = ensure() ?: return
        val t = ms.coerceIn(0, (durationMs - 200).coerceAtLeast(0))
        p.seekTo(t, MediaPlayer.SEEK_CLOSEST)
        pos = t
        when (play) {
            true -> start()
            false -> pause()
            null -> {}
        }
    }

    private fun start() {
        val p = ensure() ?: return
        if (pos >= durationMs - 300) p.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
        runCatching { p.playbackParams = PlaybackParams().setSpeed(speed) }
        p.start()
        playing = true
    }

    fun pause() {
        runCatching { mp?.pause() }
        playing = false
    }

    fun toggle() {
        clipEnd = -1L
        if (playing) pause() else start()
    }

    fun skip(delta: Long) = seek(pos + delta)

    fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        if (playing) runCatching { mp?.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    fun release() {
        runCatching { mp?.release() }
        mp = null
        playing = false
    }
}

/**
 * 底部播放条：
 * 上面一条彩色细条是「谁在什么时候说话」，橙点是录音时的标记；
 * 拖动进度条，转写稿跟着滚到对应的话。
 */
@Composable
fun PlayerDock(
    player: PlayerState,
    sentences: List<Sentence>,
    colors: (Int) -> Color,
    marks: List<Mark>,
    scrub: Long?,
    onScrub: (Long?) -> Unit,
    onSeekDone: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val dur = player.durationMs.coerceAtLeast(1)
    val shown = scrub ?: player.pos
    val nextMark = marks.filterNot { it.gap }.map { it.atMs }.sorted().firstOrNull { it > shown + 1500 }
    Column(
        modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Palette.Card)
            // 播放条上的空白处也要吃掉触摸，不然会点穿到下面的转写稿
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)
    ) {
        Scrubber(player, sentences, colors, marks, scrub, onScrub, onSeekDone)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            T(clock(shown), 12, if (scrub != null) Palette.Ink else Palette.Sub, FontWeight.SemiBold, tabular = true)
            Spacer(Modifier.weight(1f))
            T(clock(player.durationMs), 12, Palette.Sub, tabular = true)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .width(56.dp)
                    .height(34.dp)
                    .pressable { player.cycleSpeed() }
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Faint),
                contentAlignment = Alignment.Center,
            ) { T(speedText(player.speed), 13, Palette.Ink, FontWeight.SemiBold, tabular = true) }
            SkipButton("-15") { player.skip(-15_000) }
            Box(
                Modifier
                    .size(60.dp)
                    .pressable(scaleTo = 0.9f) { player.toggle() }
                    .clip(CircleShape)
                    .background(Palette.Island),
                contentAlignment = Alignment.Center,
            ) { Ico(if (player.playing) R.drawable.ic_pause else R.drawable.ic_play, 28.dp, Color.White) }
            SkipButton("+15") { player.skip(15_000) }
            Row(
                Modifier
                    .width(56.dp)
                    .height(34.dp)
                    .pressable(enabled = nextMark != null) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        nextMark?.let { onSeekDone(it) }
                    }
                    .clip(RoundedCornerShape(50))
                    .background(if (nextMark != null) Palette.AccentSoft else Palette.Faint),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(R.drawable.ic_star, 14.dp, if (nextMark != null) Palette.Accent else Palette.Hint)
                T("下个", 12, if (nextMark != null) Palette.AccentText else Palette.Hint, FontWeight.SemiBold)
            }
        }
    }
}

private fun speedText(s: Float) = when (s) {
    1f -> "1×"
    1.25f -> "1.25×"
    1.5f -> "1.5×"
    else -> "2×"
}

@Composable
private fun SkipButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).pressable(scaleTo = 0.88f, onClick = onClick).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) { T(text, 16, Palette.Ink, FontWeight.Bold, tabular = true) }
}

@Composable
private fun Scrubber(
    player: PlayerState,
    sentences: List<Sentence>,
    colors: (Int) -> Color,
    marks: List<Mark>,
    scrub: Long?,
    onScrub: (Long?) -> Unit,
    onSeekDone: (Long) -> Unit,
) {
    val view = LocalView.current
    val dur = player.durationMs.coerceAtLeast(1)
    val scrubNow by rememberUpdatedState(scrub)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(dur) {
                detectTapGestures { o ->
                    val t = (o.x / size.width * dur).toLong().coerceIn(0, dur)
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSeekDone(t)
                }
            }
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onScrub((o.x / size.width * dur).toLong().coerceIn(0, dur))
                    },
                    onDragEnd = {
                        scrubNow?.let { onSeekDone(it) }
                        onScrub(null)
                    },
                    onDragCancel = { onScrub(null) },
                ) { change, _ ->
                    change.consume()
                    onScrub((change.position.x / size.width * dur).toLong().coerceIn(0, dur))
                }
            }
    ) {
        val w = size.width
        val stripY = 6.dp.toPx()
        val stripH = 6.dp.toPx()
        // 谁在什么时候说话
        drawRoundRect(Palette.Faint, Offset(0f, stripY), Size(w, stripH), CornerRadius(stripH / 2))
        sentences.forEach { s ->
            val x0 = s.begin.toFloat() / dur * w
            val x1 = (s.end.toFloat() / dur * w).coerceAtLeast(x0 + 1f)
            drawRect(colors(s.spk).copy(alpha = 0.85f), Offset(x0, stripY), Size(x1 - x0, stripH))
        }
        // 标记
        marks.forEach { m ->
            val x = m.atMs.toFloat() / dur * w
            if (m.gap) {
                // 没录到的一段：灰色盖住
                val x1 = (m.endMs.toFloat() / dur * w).coerceAtLeast(x + 2f)
                drawRect(Palette.Line, Offset(x, stripY), Size(x1 - x, stripH))
            } else drawCircle(Palette.Accent, 3.5.dp.toPx(), Offset(x, stripY - 1.dp.toPx()))
        }
        // 进度
        val trackY = 26.dp.toPx()
        val trackH = 4.dp.toPx()
        val cur = ((scrub ?: player.pos).toFloat() / dur * w).coerceIn(0f, w)
        drawRoundRect(Palette.Line, Offset(0f, trackY - trackH / 2), Size(w, trackH), CornerRadius(trackH / 2))
        drawRoundRect(Palette.Ink, Offset(0f, trackY - trackH / 2), Size(cur, trackH), CornerRadius(trackH / 2))
        // 拖的时候有一根竖线连到上面的彩条，看得出拖到了谁说的那段
        if (scrub != null) {
            drawLine(Palette.Ink.copy(alpha = 0.35f), Offset(cur, stripY), Offset(cur, trackY), 1.5.dp.toPx())
        }
        drawCircle(Palette.Ink, (if (scrub != null) 10 else 7).dp.toPx(), Offset(cur, trackY))
        drawCircle(Color.White, (if (scrub != null) 4f else 2.5f).dp.toPx(), Offset(cur, trackY))
    }
}
