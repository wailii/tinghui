package com.xiaodeng.tinghui

import org.json.JSONArray
import org.json.JSONObject

enum class Status { Recording, Queued, Uploading, Transcribing, Matching, Done, Failed }

/** 纪要（大模型）的状态，和转写分开：转写好了就能看稿，纪要慢慢来 */
enum class SumState { None, Queued, Working, Done, Failed }

/** 录音时的标记。endMs > 0 的是「这段没录到」（通话或别的 App 占着麦克风） */
data class Mark(val atMs: Long, val note: String = "", val endMs: Long = 0) {
    val gap get() = endMs > 0
}

/** 一句话；spk 是本场会里的说话人序号（Speaker.idx） */
data class Sentence(val begin: Long, val end: Long, val spk: Int, val text: String)

/**
 * 本场会里的一个说话人。
 * personId：认定是声纹库里的谁（自动认出或手动指定）；auto=true 表示是自动认出的
 * guessId：拿不准时猜的人，界面上显示成「张三？」
 * emb：这个人在本场会里的声纹，给他起名时存进声纹库
 */
data class Speaker(
    val idx: Int,
    val personId: String? = null,
    val auto: Boolean = false,
    val guessId: String? = null,
    val score: Float = 0f,
    val talkMs: Long = 0,
    val emb: FloatArray? = null,
    /** 长会切段后属于第几段（声纹合段用） */
    val chunk: Int = 0,
    /** 这场的声音已经存进了谁的声纹库（改名时要撤掉） */
    val enrolled: String? = null,
    /** 你说过「不是他」的人（逗号隔开），回头再认时跳过 */
    val notId: String? = null,
) {
    fun rejects(pid: String) = notId?.split(',')?.contains(pid) == true
}

/** 一段送去转写的音频。超过两小时的会切成几段，每段单独转 */
data class Chunk(
    val file: String,
    val offsetMs: Long,
    val durationMs: Long,
    val ossUrl: String? = null,
    val ossAt: Long = 0,
    val taskId: String? = null,
    val done: Boolean = false,
)

data class Rec(
    val id: String,
    val title: String,
    val startedAt: Long,
    val durationMs: Long,
    val audio: String,
    val imported: Boolean = false,
    val status: Status = Status.Recording,
    val error: String? = null,
    val marks: List<Mark> = emptyList(),
    val chunks: List<Chunk> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val inTokens: Long = 0,
    val outTokens: Long = 0,
    val sentenceCount: Int = 0,
    /** 列表里显示的开头几句 */
    val preview: String = "",
    /** 大模型写的一句话摘要（30 字以内），列表里显示 */
    val brief: String = "",
    /** 大模型写的纪要（Markdown） */
    val summary: String = "",
    val sumState: SumState = SumState.None,
    val sumError: String? = null,
    val sumModel: String = "",
    /** 分类（文件夹），null 是未分类 */
    val folder: String? = null,
    /** 标题、分类被你手动改过，就不让大模型再动 */
    val titleManual: Boolean = false,
    val folderManual: Boolean = false,
    /** 声纹在后台做完没有（认出熟人、长会合段） */
    val voiceDone: Boolean = true,
    /** 出纪要时每个说话人叫什么；之后改了名，显示纪要时自动换成新名字 */
    val sumNames: Map<Int, String> = emptyMap(),
    /** 用哪个模板出的纪要（模板 id） */
    val sumTemplate: String = "",
    /** 合并掉的说话人 → 合到了谁 */
    val alias: Map<Int, Int> = emptyMap(),
    /** 从飞书日历开录的：日程 id、给大模型看的日程信息（标题、参会人）、参会人名字（转写热词） */
    val eventUid: String = "",
    val event: String = "",
    val hot: List<String> = emptyList(),
    /** 从纪要里拆出来的待办 */
    val todos: List<Todo> = emptyList(),
    /** 存到飞书的东西：文档、录音、多维表格里的行 */
    val fs: FsState = FsState(),
) {
    val summarizing get() = sumState == SumState.Queued || sumState == SumState.Working

    val busy get() = status in setOf(Status.Queued, Status.Uploading, Status.Transcribing, Status.Matching)

    /** 百炼 qwen-audio-3.1-asr-flash-filetrans 北京价：输入 0.8 元、输出 2.7 元 / 百万 token */
    val costYuan get() = inTokens * 0.8 / 1e6 + outTokens * 2.7 / 1e6

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("startedAt", startedAt); put("durationMs", durationMs)
        put("audio", audio); put("imported", imported); put("status", status.name); put("error", error)
        put("inTokens", inTokens); put("outTokens", outTokens); put("sentenceCount", sentenceCount); put("preview", preview)
        put("summary", summary); put("brief", brief); put("sumState", sumState.name); put("sumError", sumError); put("sumModel", sumModel)
        put("folder", folder); put("titleManual", titleManual); put("folderManual", folderManual)
        put("voiceDone", voiceDone); put("sumTemplate", sumTemplate)
        put("sumNames", JSONObject().apply { sumNames.forEach { (k, v) -> put(k.toString(), v) } })
        put("alias", JSONObject().apply { alias.forEach { (k, v) -> put(k.toString(), v) } })
        put("eventUid", eventUid); put("event", event); put("hot", JSONArray(hot))
        put("todos", Todos.toJson(todos)); put("fs", fs.toJson())
        put("marks", JSONArray(marks.map { JSONObject().put("at", it.atMs).put("note", it.note).put("end", it.endMs) }))
        put("chunks", JSONArray(chunks.map {
            JSONObject().put("file", it.file).put("offset", it.offsetMs).put("dur", it.durationMs)
                .put("oss", it.ossUrl).put("ossAt", it.ossAt).put("task", it.taskId).put("done", it.done)
        }))
        put("speakers", JSONArray(speakers.map { s ->
            JSONObject().put("idx", s.idx).put("person", s.personId).put("auto", s.auto)
                .put("guess", s.guessId).put("score", s.score.toDouble()).put("talk", s.talkMs)
                .put("emb", s.emb?.let { e -> JSONArray(e.map { it.toDouble() }) }).put("chunk", s.chunk).put("enrolled", s.enrolled).put("not", s.notId)
        }))
    }

    companion object {
        fun fromJson(o: JSONObject) = Rec(
            id = o.getString("id"),
            title = o.optString("title"),
            startedAt = o.optLong("startedAt"),
            durationMs = o.optLong("durationMs"),
            audio = o.optString("audio", "audio.aac"),
            imported = o.optBoolean("imported"),
            status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.Queued),
            error = o.optStr("error"),
            inTokens = o.optLong("inTokens"),
            outTokens = o.optLong("outTokens"),
            sentenceCount = o.optInt("sentenceCount"),
            preview = o.optString("preview"),
            summary = o.optString("summary"),
            brief = o.optString("brief"),
            sumState = runCatching { SumState.valueOf(o.optString("sumState")) }.getOrDefault(SumState.None),
            sumError = o.optStr("sumError"),
            sumModel = o.optString("sumModel"),
            folder = o.optStr("folder"),
            titleManual = o.optBoolean("titleManual"),
            folderManual = o.optBoolean("folderManual"),
            voiceDone = o.optBoolean("voiceDone", true),
            sumTemplate = o.optString("sumTemplate"),
            sumNames = o.optJSONObject("sumNames")?.let { m -> m.keys().asSequence().associate { it.toInt() to m.getString(it) } } ?: emptyMap(),
            alias = o.optJSONObject("alias")?.let { m -> m.keys().asSequence().associate { it.toInt() to m.getInt(it) } } ?: emptyMap(),
            eventUid = o.optString("eventUid"),
            event = o.optString("event"),
            hot = o.optJSONArray("hot")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            todos = Todos.fromJson(o.optJSONArray("todos")),
            fs = FsState.fromJson(o.optJSONObject("fs")),
            marks = o.optJSONArray("marks").objs().map { Mark(it.optLong("at"), it.optString("note"), it.optLong("end")) },
            chunks = o.optJSONArray("chunks").objs().map {
                Chunk(
                    it.getString("file"), it.optLong("offset"), it.optLong("dur"), it.optStr("oss"),
                    it.optLong("ossAt"), it.optStr("task"), it.optBoolean("done"),
                )
            },
            speakers = o.optJSONArray("speakers").objs().map {
                Speaker(
                    it.optInt("idx"), it.optStr("person"), it.optBoolean("auto"), it.optStr("guess"),
                    it.optDouble("score", 0.0).toFloat(), it.optLong("talk"),
                    it.optJSONArray("emb")?.let { a -> FloatArray(a.length()) { i -> a.getDouble(i).toFloat() } },
                    it.optInt("chunk"),
                    it.optStr("enrolled"),
                    it.optStr("not"),
                )
            },
        )
    }
}

fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

/** optString 遇到 null 会返回 "null" 字符串，这里统一成真 null */
fun JSONObject.optStr(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }

fun sentencesToJson(list: List<Sentence>) = JSONArray(list.map {
    JSONObject().put("b", it.begin).put("e", it.end).put("s", it.spk).put("t", it.text)
})

fun sentencesFromJson(a: JSONArray) = a.objs().map {
    Sentence(it.optLong("b"), it.optLong("e"), it.optInt("s"), it.optString("t"))
}
