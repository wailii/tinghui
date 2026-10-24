package com.xiaodeng.tinghui

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * App 自己录的是 AAC ADTS 裸流：一帧一帧首尾相接，录到一半被杀也能读到最后一帧。
 * 这里按帧数时长、按帧切段（超过两小时的会要切开送去转写）。
 */
object Adts {
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun isAdts(f: File): Boolean = runCatching {
        FileInputStream(f).use { val b = ByteArray(2); it.read(b) == 2 && b[0].toInt() and 0xFF == 0xFF && b[1].toInt() and 0xF0 == 0xF0 }
    }.getOrDefault(false)

    private class Frame(val offset: Long, val length: Int, val durUs: Long)

    private fun frames(f: File): List<Frame> {
        val out = ArrayList<Frame>()
        BufferedInputStream(FileInputStream(f), 1 shl 16).use { inp ->
            val h = ByteArray(7)
            var off = 0L
            while (true) {
                if (readFully(inp, h) < 7) break
                if (h[0].toInt() and 0xFF != 0xFF || h[1].toInt() and 0xF0 != 0xF0) break
                val len = ((h[3].toInt() and 0x03) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xE0) ushr 5)
                if (len < 7) break
                val rate = RATES.getOrElse((h[2].toInt() ushr 2) and 0x0F) { 16000 }
                val blocks = (h[6].toInt() and 0x03) + 1
                val rest = len - 7L
                if (inp.skipAll(rest) < rest) break // 最后一帧没写完，丢掉
                out += Frame(off, len, blocks * 1024L * 1_000_000 / rate)
                off += len
            }
        }
        return out
    }

    private fun readFully(inp: BufferedInputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val r = inp.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun BufferedInputStream.skipAll(n: Long): Long {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) {
                if (read() < 0) break
                left--
            } else left -= s
        }
        return n - left
    }

    fun durationMs(f: File): Long = frames(f).sumOf { it.durUs } / 1000

    /** 按 maxMs 切成几段等长的，返回 (文件名, 起点毫秒, 时长毫秒) */
    fun split(f: File, maxMs: Long): List<Triple<String, Long, Long>> {
        val fr = frames(f)
        val total = fr.sumOf { it.durUs } / 1000
        val n = ((total + maxMs - 1) / maxMs).coerceAtLeast(1)
        if (n == 1L) return listOf(Triple(f.name, 0L, total))
        val per = total / n
        val out = ArrayList<Triple<String, Long, Long>>()
        var i = 0
        var t = 0L
        FileInputStream(f).channel.use { ch ->
            for (k in 0 until n.toInt()) {
                val startT = t
                val first = i
                while (i < fr.size && (k == n.toInt() - 1 || (t - startT) / 1000 < per)) {
                    t += fr[i].durUs
                    i++
                }
                if (i == first) break
                val name = "part_$k.aac"
                FileOutputStream(File(f.parentFile, name)).channel.use { o ->
                    val from = fr[first].offset
                    val to = fr[i - 1].offset + fr[i - 1].length
                    var pos = from
                    while (pos < to) pos += ch.transferTo(pos, to - pos, o)
                }
                out += Triple(name, startT / 1000, (t - startT) / 1000)
            }
        }
        return out
    }
}
