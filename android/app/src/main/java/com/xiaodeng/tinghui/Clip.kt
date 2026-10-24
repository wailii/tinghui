package com.xiaodeng.tinghui

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * 从录音里切一段原话，转成飞书语音消息要的 ogg/opus（16k 单声道），群里点开就能听。
 * 全在手机上用系统编解码器做，不传整段录音
 */
object Clip {
    private const val RATE = 16000
    private const val TIMEOUT = 10_000L

    /** 切 [startMs, startMs + lenMs) 写到 out，返回实际时长（毫秒） */
    fun opus(src: File, startMs: Long, lenMs: Long, out: File): Long {
        val pcm = decode(src, startMs.coerceAtLeast(0) * 1000, (startMs + lenMs) * 1000)
        if (pcm.isEmpty()) throw IllegalStateException("这段没切出声音")
        encode(pcm, out)
        return pcm.size * 1000L / RATE
    }

    /** 解出这段的 PCM，混成单声道、换成 16k */
    private fun decode(src: File, startUs: Long, endUs: Long): ShortArray {
        val ex = MediaExtractor()
        ex.setDataSource(src.path)
        val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IllegalStateException("录音里没有声音轨")
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0)
        dec.start()
        // 找不到精确位置的格式（ADTS）会从更前面开始解，下面按时间丢掉多的
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val mono = Shorts(((endUs - startUs) / 1000 * 48).toInt().coerceAtLeast(1024))
        val info = MediaCodec.BufferInfo()
        var inDone = false
        try {
            while (true) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        val t = ex.sampleTime
                        if (n < 0 || t > endUs) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, t, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val t = info.presentationTimeUs
                    if (info.size > 0 && t + 100_000 >= startUs && t < endUs) {
                        val b = dec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        if (float) {
                            val fb = b.asFloatBuffer()
                            while (fb.remaining() >= ch) {
                                var s = 0f
                                repeat(ch) { s += fb.get() }
                                mono.add((s / ch * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                            }
                        } else {
                            val sb = b.asShortBuffer()
                            while (sb.remaining() >= ch) {
                                var s = 0
                                repeat(ch) { s += sb.get() }
                                mono.add((s / ch).toShort())
                            }
                        }
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { dec.stop() }
            dec.release()
            ex.release()
        }
        return resample(mono.toArray(), rate)
    }

    /** 会变长的 short 数组：几十秒的采样不装箱 */
    private class Shorts(cap: Int) {
        private var a = ShortArray(cap)
        private var n = 0
        fun add(v: Short) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray() = a.copyOf(n)
    }

    /** 线性插值换采样率；本来就是 16k 的原样返回 */
    private fun resample(src: ShortArray, from: Int): ShortArray {
        if (from == RATE || src.isEmpty()) return src
        val n = (src.size.toLong() * RATE / from).toInt()
        return ShortArray(n) { i ->
            val x = i.toDouble() * from / RATE
            val a = x.toInt().coerceAtMost(src.size - 1)
            val b = (a + 1).coerceAtMost(src.size - 1)
            (src[a] + (src[b] - src[a]) * (x - a)).toInt().toShort()
        }
    }

    private fun encode(pcm: ShortArray, out: File) {
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
        }
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        out.delete()
        val fos = out.outputStream().buffered()
        var ogg: OggOpus? = null
        var head: ByteArray? = null
        var pos = 0
        var inDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inDone) {
                    val i = enc.dequeueInputBuffer(TIMEOUT)
                    if (i >= 0) {
                        val buf = enc.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.clear()
                        val n = minOf(buf.capacity() / 2, pcm.size - pos)
                        val pts = pos * 1_000_000L / RATE
                        if (n <= 0) {
                            enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            buf.asShortBuffer().put(pcm, pos, n)
                            enc.queueInputBuffer(i, 0, n * 2, pts, 0)
                            pos += n
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, TIMEOUT)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 编码器给的 OpusHead 里有它自己的 pre-skip
                    enc.outputFormat.getByteBuffer("csd-0")?.let { b ->
                        val h = ByteArray(b.remaining()).also { b.get(it) }
                        if (h.size >= 19 && String(h, 0, 8) == "OpusHead") head = h
                    }
                } else if (o >= 0) {
                    val b = enc.getOutputBuffer(o)!!
                    b.position(info.offset)
                    b.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size).also { b.get(it) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        if (head == null && bytes.size >= 19 && String(bytes, 0, 8) == "OpusHead") head = bytes
                    } else if (bytes.isNotEmpty()) {
                        val w = ogg ?: OggOpus(fos, head ?: OggOpus.head()).also { ogg = it }
                        w.add(bytes)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            (ogg ?: OggOpus(fos, head ?: OggOpus.head())).close()
        } finally {
            runCatching { enc.stop() }
            enc.release()
            fos.close()
        }
    }
}
