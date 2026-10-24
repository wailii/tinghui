package com.xiaodeng.tinghui

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * 把音频里指定的几个时间段解码成 16k 单声道 float（声纹模型要这个格式）。
 * 整场两小时解成 PCM 太大，只留需要的段；段与段离得远就直接跳过去。
 */
object AudioDecode {
    private const val OUT_RATE = 16000
    private const val SEEK_GAP_US = 8_000_000L

    /** ranges：按开始时间排好、互不重叠的 [开始毫秒, 结束毫秒] */
    fun collect(f: File, ranges: List<LongArray>, onRange: (Int, FloatArray) -> Unit) {
        if (ranges.isEmpty()) return
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (track == null) {
            ex.release()
            return
        }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        var ri = 0
        var acc = FloatArray(0)
        var accN = 0
        fun startRange() {
            val r = ranges[ri]
            acc = FloatArray(((r[1] - r[0]) * OUT_RATE / 1000 + 16).toInt())
            accN = 0
        }
        var pendingSeekMs: Long? = ranges[0][0].takeIf { it * 1000 > SEEK_GAP_US }
        fun finishRange(bufEndUs: Long) {
            if (accN > OUT_RATE / 2) onRange(ri, acc.copyOf(accN))
            ri++
            if (ri < ranges.size) {
                startRange()
                if (ranges[ri][0] * 1000 - bufEndUs > SEEK_GAP_US) pendingSeekMs = ranges[ri][0]
            }
        }
        startRange()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var mono = FloatArray(0)
        try {
            loop@ while (ri < ranges.size) {
                pendingSeekMs?.let { ms ->
                    ex.seekTo(max(0L, ms - 1500) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    codec.flush()
                    inputDone = false
                    pendingSeekMs = null
                }
                // 能喂多少喂多少，别每轮都干等
                while (!inputDone) {
                    val ii = codec.dequeueInputBuffer(0)
                    if (ii < 0) break
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 2_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    continue
                }
                if (oi < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val frames = info.size / 2 / ch
                val bs = info.presentationTimeUs
                val be = bs + frames * 1_000_000L / sr
                // 这一块和要的时间段不沾边就不转换，省掉大部分计算
                if (frames > 0 && ranges[ri][0] * 1000 < be) {
                    val out = codec.getOutputBuffer(oi)!!
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                    if (mono.size < frames) mono = FloatArray(frames)
                    for (i in 0 until frames) {
                        var s = 0
                        for (c in 0 until ch) s += sb.get(i * ch + c)
                        mono[i] = s / (32768f * ch)
                    }
                    while (ri < ranges.size) {
                        val rs = ranges[ri][0] * 1000
                        val re = ranges[ri][1] * 1000
                        if (re <= bs) {
                            finishRange(be)
                            continue
                        }
                        if (rs >= be) break
                        val os = max(rs, bs)
                        val oe = min(re, be)
                        val n0 = ((os - rs) * OUT_RATE / 1_000_000).toInt()
                        val n1 = min(((oe - rs) * OUT_RATE / 1_000_000).toInt(), acc.size)
                        for (n in n0 until n1) {
                            val t = rs + n * 1_000_000L / OUT_RATE
                            val pos = (t - bs).toDouble() * sr / 1_000_000
                            val i = pos.toInt().coerceIn(0, frames - 1)
                            val j = min(i + 1, frames - 1)
                            val fr = (pos - i).toFloat().coerceIn(0f, 1f)
                            acc[n] = mono[i] * (1 - fr) + mono[j] * fr
                        }
                        if (n1 > accN) accN = n1
                        if (re <= be) finishRange(be) else break
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                if (eos) break
            }
            if (ri < ranges.size && accN > OUT_RATE / 2) onRange(ri, acc.copyOf(accN))
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
    }
}
