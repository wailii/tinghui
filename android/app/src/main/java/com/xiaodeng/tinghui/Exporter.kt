package com.xiaodeng.tinghui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** 一段话：同一个人连着说的几句并成一段 */
data class Para(val spk: Int, val begin: Long, val end: Long, val text: String, val parts: List<Sentence>)

object Exporter {
    fun paragraphs(list: List<Sentence>): List<Para> {
        val out = ArrayList<Para>()
        for (s in list) {
            val last = out.lastOrNull()
            if (last != null && last.spk == s.spk && last.text.length < 600 && s.begin - last.end < 20_000) {
                out[out.size - 1] = last.copy(end = s.end, text = last.text + s.text, parts = last.parts + s)
            } else out += Para(s.spk, s.begin, s.end, s.text, listOf(s))
        }
        return out
    }

    fun markdown(r: Rec, list: List<Sentence>): String {
        val names = r.speakers.associate { it.idx to Store.speakerName(it) }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(r.startedAt)
        val marks = r.marks.sortedBy { it.atMs }.toMutableList()
        return buildString {
            append("# ${r.title}\n\n")
            append("$date · 时长 ${clock(r.durationMs)} · 说话人：${r.speakers.joinToString("、") { names[it.idx]!! }}\n\n")
            if (r.folder != null) append("分类：${r.folder}\n\n")
            if (r.summary.isNotBlank()) {
                append("## 纪要\n\n")
                append(Chart.flatten(Store.minutes(r)).lines().joinToString("\n") { if (it.startsWith("#")) "#$it" else it })
                append("\n\n## 转写\n\n")
            }
            if (r.marks.any { !it.gap }) append("★ 是录音时手动标记的重要位置。\n\n")
            append("---\n\n")
            for (p in paragraphs(list)) {
                while (marks.isNotEmpty() && marks.first().atMs <= p.begin) {
                    val m = marks.removeAt(0)
                    if (m.gap) append("> [${clock(m.atMs)}] 这里 ${clock(m.endMs - m.atMs)} 没录到（麦克风被通话或别的 App 占用）\n\n")
                    else append("> ★ [${clock(m.atMs)}] 标记${if (m.note.isNotBlank()) "：${m.note}" else ""}\n\n")
                }
                append("**[${clock(p.begin)}] ${names[p.spk] ?: "说话人 ${p.spk + 1}"}**：${p.text}\n\n")
            }
            marks.filterNot { it.gap }.forEach { append("> ★ [${clock(it.atMs)}] 标记\n\n") }
        }
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(60)

    private fun shareFile(ctx: Context, f: File, mime: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(f.name, uri)
        ctx.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareTranscript(ctx: Context, r: Rec) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "${safeName(r.title)}.md")
        f.writeText(markdown(r, Store.sentences(r.id)))
        shareFile(ctx, f, "text/markdown", "把转写稿发到…")
    }

    fun shareAudio(ctx: Context, r: Rec) {
        val src = Store.audio(r)
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "${safeName(r.title)}.${src.extension.ifEmpty { "aac" }}")
        // 两小时的录音几十 MB，拷贝放后台
        kotlin.concurrent.thread {
            runCatching { src.copyTo(f, overwrite = true) }
            android.os.Handler(android.os.Looper.getMainLooper()).post { shareFile(ctx, f, "audio/*", "把录音发到…") }
        }
    }

    fun copyMinutes(ctx: Context, r: Rec) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(r.title, "# ${r.title}\n\n${Chart.flatten(Store.minutes(r))}"))
        Tip.show("纪要已复制")
    }

    fun copyAll(ctx: Context, r: Rec) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(r.title, markdown(r, Store.sentences(r.id))))
        Tip.show("已复制全文")
    }
}
