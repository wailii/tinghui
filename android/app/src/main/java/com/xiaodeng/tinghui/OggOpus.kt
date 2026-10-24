package com.xiaodeng.tinghui

import java.io.OutputStream

/**
 * 把 Opus 包装成 .opus（OGG）文件，排法和 ffmpeg 一样：头一页 OpusHead、一页 OpusTags，
 * 之后每 50 个包一页，页上的位置写这一页结束时一共多少样本（按 48k 算，含 pre-skip），最后一页标结束。
 * 系统自带的 MediaMuxer 一包一页、位置写的是包开头，飞书播出来是滋滋声
 */
class OggOpus(private val out: OutputStream, head: ByteArray) {
    private val serial = 0x5446
    private var seq = 0
    private var granule = (head[10].toLong() and 0xff) or ((head[11].toLong() and 0xff) shl 8)
    private val pending = ArrayList<ByteArray>()

    init {
        page(2, 0, listOf(head))
        val vendor = "tinghui".toByteArray()
        page(0, 0, listOf("OpusTags".toByteArray() + le32(vendor.size) + vendor + le32(0)))
    }

    fun add(packet: ByteArray) {
        if (packet.isEmpty()) return
        pending += packet
        granule += samples(packet)
        if (pending.size == 50) flush(last = false)
    }

    fun close() {
        flush(last = true)
        out.flush()
    }

    private fun flush(last: Boolean) {
        if (pending.isEmpty() && !last) return
        page(if (last) 4 else 0, granule, pending.toList())
        pending.clear()
    }

    private fun page(type: Int, gran: Long, packets: List<ByteArray>) {
        val lace = ArrayList<Byte>()
        for (p in packets) {
            repeat(p.size / 255) { lace += 255.toByte() }
            lace += (p.size % 255).toByte()
        }
        val hdr = ByteArray(27 + lace.size)
        "OggS".toByteArray().copyInto(hdr, 0)
        hdr[4] = 0
        hdr[5] = type.toByte()
        for (i in 0 until 8) hdr[6 + i] = (gran ushr (8 * i)).toByte()
        for (i in 0 until 4) hdr[14 + i] = (serial ushr (8 * i)).toByte()
        for (i in 0 until 4) hdr[18 + i] = (seq ushr (8 * i)).toByte()
        hdr[26] = lace.size.toByte()
        lace.forEachIndexed { i, b -> hdr[27 + i] = b }
        val body = packets.fold(ByteArray(0)) { a, p -> a + p }
        val c = crc(crc(0, hdr), body)
        for (i in 0 until 4) hdr[22 + i] = (c ushr (8 * i)).toByte()
        out.write(hdr)
        out.write(body)
        seq++
    }

    companion object {
        private val TABLE = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }

        fun crc(start: Int, b: ByteArray): Int {
            var c = start
            for (x in b) c = (c shl 8) xor TABLE[((c ushr 24) xor (x.toInt() and 0xff)) and 0xff]
            return c
        }

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

        /** 一个 Opus 包有多少样本（48k）：看开头那个字节里的帧长和帧数 */
        fun samples(p: ByteArray): Int {
            val toc = p[0].toInt() and 0xff
            val config = toc ushr 3
            val frame = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
                config < 16 -> intArrayOf(480, 960)[config and 1]
                else -> intArrayOf(120, 240, 480, 960)[config and 3]
            }
            val n = when (toc and 3) {
                0 -> 1
                3 -> if (p.size > 1) p[1].toInt() and 0x3f else 1
                else -> 2
            }
            return frame * n
        }

        /** 编码器没给头的时候自己写一个：单声道、原始 16k */
        fun head(preSkip: Int = 312, rate: Int = 16000): ByteArray =
            "OpusHead".toByteArray() + byteArrayOf(1, 1, preSkip.toByte(), (preSkip ushr 8).toByte()) +
                le32(rate) + byteArrayOf(0, 0, 0)
    }
}
