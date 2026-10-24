package com.xiaodeng.tinghui

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** 一场会存到飞书的东西：两个文档、录音文件、会议库里的行 */
data class FsState(
    val folder: String = "",
    val minutesDoc: String = "",
    val transcriptDoc: String = "",
    val audio: String = "",
    val meetRow: String = "",
    val todoRows: List<String> = emptyList(),
    val pointRows: List<String> = emptyList(),
    /** 存的时候纪要长什么样：纪要变了才重写 */
    val sumHash: Int = 0,
    val textHash: Int = 0,
    val syncedAt: Long = 0,
    val error: String = "",
    /** 纪要发到过的群：群里的人问 CodeX 时，只能查到发到过这个群的会 */
    val sharedTo: List<String> = emptyList(),
) {
    val minutesUrl get() = if (minutesDoc.isBlank()) "" else "https://feishu.cn/docx/$minutesDoc"
    val transcriptUrl get() = if (transcriptDoc.isBlank()) "" else "https://feishu.cn/docx/$transcriptDoc"
    val audioUrl get() = if (audio.isBlank()) "" else "https://feishu.cn/file/$audio"

    fun toJson(): JSONObject = JSONObject().put("folder", folder).put("minutesDoc", minutesDoc).put("transcriptDoc", transcriptDoc)
        .put("audio", audio).put("meetRow", meetRow).put("todoRows", JSONArray(todoRows)).put("pointRows", JSONArray(pointRows))
        .put("sumHash", sumHash).put("textHash", textHash).put("syncedAt", syncedAt).put("error", error)
        .put("sharedTo", JSONArray(sharedTo))

    companion object {
        fun fromJson(o: JSONObject?): FsState {
            o ?: return FsState()
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            return FsState(
                o.optString("folder"), o.optString("minutesDoc"), o.optString("transcriptDoc"), o.optString("audio"),
                o.optString("meetRow"), list("todoRows"), list("pointRows"), o.optInt("sumHash"), o.optInt("textHash"),
                o.optLong("syncedAt"), o.optString("error"), list("sharedTo"),
            )
        }
    }
}

/**
 * 把会议存进飞书：云空间「听会」文件夹，按分类分子文件夹；每场会一份纪要、一份转写稿、一个录音文件；
 * 「听会 · 会议库」多维表格里会议、待办、结论各一张表。机器人建的东西都转给你，所有者是你。
 * 同一场会再存一次是更新，不会重复建
 */
object FeishuSync {
    const val ROOT_NAME = "听会"
    private const val UNFILED = "未分类"

    private fun field(name: String, type: Int, prop: JSONObject? = null) =
        JSONObject().put("field_name", name).put("type", type).apply { if (prop != null) put("property", prop) }

    private fun options(vararg names: String) = JSONObject().put("options", JSONArray(names.map { JSONObject().put("name", it) }))
    private val DATE = JSONObject().put("date_formatter", "yyyy/MM/dd HH:mm")

    /** 第一次用：建「听会」文件夹和会议库；以前建过就直接用 */
    @Synchronized
    fun ensureSpace(): Prefs.Space {
        var sp = Prefs.space.value
        if (sp.ready) return sp
        if (sp.folder.isBlank()) {
            val f = Feishu.createFolder(ROOT_NAME, Feishu.rootFolder())
            Feishu.transfer(f, "folder", stayPut = false)
            sp = sp.copy(folder = f)
            Prefs.setSpace(sp)
        }
        if (sp.base.isBlank()) {
            val b = Feishu.createBase("听会 · 会议库", sp.folder)
            Feishu.transfer(b.token, "bitable", stayPut = true)
            sp = sp.copy(base = b.token, baseUrl = b.url)
            Prefs.setSpace(sp)
        }
        if (sp.tMeet.isBlank()) {
            val base = sp.base
            // 会议库里已经有这几张表（以前建过、重装了）就接着用
            val had = Feishu.tables(base)
            val meet = had["会议"] ?: Feishu.createTable(
                base, "会议", JSONArray()
                    .put(field("会名", 1))
                    .put(field("开始", 5, DATE))
                    .put(field("时长（分钟）", 2))
                    .put(field("分类", 3))
                    .put(field("参会人", 1))
                    .put(field("一句话", 1))
                    .put(field("纪要", 15))
                    .put(field("转写稿", 15))
                    .put(field("录音", 15))
                    .put(field("听会编号", 1))
                    .put(field("发到的群", 1)),
            )
            val todo = had["待办"] ?: Feishu.createTable(
                base, "待办", JSONArray()
                    .put(field("事项", 1))
                    .put(field("负责人", 1))
                    .put(field("是我的", 7))
                    .put(field("截止", 5, DATE))
                    .put(field("状态", 3, options("没做完", "做完了")))
                    .put(field("会议", 18, JSONObject().put("table_id", meet).put("multiple", false)))
                    .put(field("会上时间", 1))
                    .put(field("飞书任务", 15))
                    .put(field("听会编号", 1)),
            )
            val point = had["结论"] ?: Feishu.createTable(
                base, "结论", JSONArray()
                    .put(field("内容", 1))
                    .put(field("类型", 3, options("已定", "没定")))
                    .put(field("会议", 18, JSONObject().put("table_id", meet).put("multiple", false)))
                    .put(field("日期", 5, JSONObject().put("date_formatter", "yyyy/MM/dd")))
                    .put(field("分类", 3))
                    .put(field("会上时间", 1))
                    .put(field("听会编号", 1)),
            )
            // 新建多维表格时飞书自带一张空表，删掉
            if (had.isNotEmpty() && had.keys.none { it in setOf("会议", "待办", "结论") })
                had.values.forEach { runCatching { Feishu.deleteTable(base, it) } }
            sp = sp.copy(tMeet = meet, tTodo = todo, tPoint = point)
            Prefs.setSpace(sp)
        }
        return sp
    }

    /** 分类对应的子文件夹；没有就建 */
    @Synchronized
    private fun folderOf(category: String?): String {
        val name = category?.takeIf { it.isNotBlank() } ?: UNFILED
        val sp = ensureSpace()
        sp.folders[name]?.let { return it }
        val f = Feishu.createFolder(name, sp.folder)
        runCatching { Feishu.transfer(f, "folder", stayPut = true) }
        Prefs.setSpace(sp.copy(folders = sp.folders + (name to f)))
        return f
    }

    private fun mmss(ms: Long) = clock(ms)

    /** 转写稿：一段一行，前面是时间和谁说的 */
    private fun transcriptMd(r: Rec, sents: List<Sentence>): String {
        val names = r.speakers.associate { it.idx to Store.speakerName(it) }
        return buildString {
            Exporter.paragraphs(sents).forEach { p ->
                append("**[${mmss(p.begin)}] ${names[p.spk] ?: "说话人 ${p.spk + 1}"}**：${p.text}\n\n")
            }
        }
    }

    private fun meta(r: Rec): String {
        val date = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(r.startedAt)
        val names = r.speakers.sortedByDescending { it.talkMs }.joinToString("、") { Store.speakerName(it) }
        return "$date · ${clock(r.durationMs)}" + if (names.isNotBlank()) " · $names" else ""
    }

    /** 纪要写进文档：图画成图片插进去，其余交给飞书转 */
    private fun writeMinutes(doc: String, r: Rec) {
        val md = Store.minutes(r)
        Feishu.appendMarkdown(doc, meta(r) + (if (r.brief.isNotBlank()) "\n\n" + r.brief else ""))
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) Feishu.appendMarkdown(doc, buf.toString())
            buf.clear()
        }
        val re = Regex("```(chart|mermaid)[ \\t]*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        var last = 0
        for (m in re.findAll(md)) {
            buf.append(md, last, m.range.first)
            last = m.range.last + 1
            val png = runCatching {
                if (m.groupValues[1] == "mermaid") Flow.png(m.groupValues[2])
                else Chart.parse(m.groupValues[2])?.let { s -> Chart.render(s, 720, Chart.Look.feishu())?.let { Chart.toPng(it) } }
            }.getOrNull()
            if (png != null) {
                flush()
                runCatching { Feishu.appendImage(doc, png) }.onFailure { buf.append(Chart.flatten(m.value)) }
            } else buf.append(Chart.flatten(m.value))
        }
        buf.append(md.substring(last))
        flush()
    }

    private fun link(url: String, text: String) = if (url.isBlank()) null else JSONObject().put("link", url).put("text", text)

    /** 查出来的文本字段是一段段的：[{text, type}] */
    private fun textOf(o: JSONObject, field: String): String =
        o.optJSONArray(field)?.let { a -> (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() } }
            ?: o.optString(field)

    /** 链接字段里的 token：https://feishu.cn/docx/xxx → xxx */
    private fun tokenOf(o: JSONObject, field: String): String =
        o.optJSONObject(field)?.optString("link").orEmpty().substringAfterLast('/').substringBefore('?')

    /**
     * 这场会以前存过、但手机上没记着（重装了、换了手机、电脑上先存过）：
     * 在会议库里按编号找，找不到再按会名 + 开始时间前后半小时找；找到就接着用原来的文档和行，不另建一份
     */
    private fun adopt(sp: Prefs.Space, r: Rec, fs: FsState): FsState {
        if (fs.meetRow.isNotBlank()) return fs
        val hit = runCatching {
            Feishu.searchRecords(sp.base, sp.tMeet, JSONArray().put(Feishu.cond("听会编号", "is", r.id))).firstOrNull()
                ?: Feishu.searchRecords(sp.base, sp.tMeet, JSONArray().put(Feishu.cond("会名", "is", r.title)))
                    .firstOrNull { kotlin.math.abs((it.optJSONObject("fields")?.optLong("开始") ?: 0L) - r.startedAt) < 30 * 60_000 }
        }.getOrNull() ?: return fs
        val f = hit.optJSONObject("fields") ?: return fs
        val oldId = textOf(f, "听会编号").ifBlank { r.id }
        // 原来那场会的待办和结论行，这次整场重写时一起删
        fun rows(table: String, op: String, v: String) = runCatching {
            Feishu.searchRecords(sp.base, table, JSONArray().put(Feishu.cond("听会编号", op, v))).map { it.getString("record_id") }
        }.getOrDefault(emptyList())
        return fs.copy(
            minutesDoc = fs.minutesDoc.ifBlank { tokenOf(f, "纪要") },
            transcriptDoc = fs.transcriptDoc.ifBlank { tokenOf(f, "转写稿") },
            audio = fs.audio.ifBlank { tokenOf(f, "录音") },
            meetRow = hit.getString("record_id"),
            sharedTo = (fs.sharedTo + textOf(f, "发到的群").split(',').filter { it.isNotBlank() }).distinct(),
            todoRows = rows(sp.tTodo, "contains", "$oldId#"),
            pointRows = rows(sp.tPoint, "is", oldId),
        )
    }

    /**
     * 存一场会。纪要、转写稿、录音、会议库都写；同一场会再存就是更新。
     * 返回存好后的 FsState
     */
    fun sync(id: String, onStep: (String) -> Unit = {}): FsState {
        val r0 = Store.get(id) ?: throw Feishu.FsError("这场会没了")
        if (r0.status != Status.Done || r0.sentenceCount == 0) throw Feishu.FsError("还没转写好")
        val sp = ensureSpace()
        var fs = adopt(sp, r0, r0.fs)
        val folder = folderOf(r0.folder)
        val title = r0.title
        // 分类改了：文档和录音跟着挪
        if (fs.folder.isNotBlank() && fs.folder != folder) {
            listOf(fs.minutesDoc to "docx", fs.transcriptDoc to "docx", fs.audio to "file").forEach { (t, type) ->
                if (t.isNotBlank()) runCatching { Feishu.move(t, type, folder) }
            }
        }
        fs = fs.copy(folder = folder)

        val sents = Store.sentences(id)
        val minutes = Store.minutes(r0)
        val sumHash = (minutes + title + r0.brief).hashCode()
        val textHash = (transcriptMd(r0, sents) + title).hashCode()

        if (minutes.isNotBlank() && (fs.minutesDoc.isBlank() || fs.sumHash != sumHash)) {
            onStep("写纪要")
            if (fs.minutesDoc.isBlank()) {
                val d = Feishu.createDoc("$title · 纪要", folder)
                runCatching { Feishu.transfer(d, "docx", stayPut = true) }
                fs = fs.copy(minutesDoc = d)
            } else {
                runCatching { Feishu.setDocTitle(fs.minutesDoc, "$title · 纪要") }
                Feishu.clearDoc(fs.minutesDoc)
            }
            writeMinutes(fs.minutesDoc, r0)
            fs = fs.copy(sumHash = sumHash)
            Store.update(id) { it.copy(fs = fs) }
        }
        if (fs.transcriptDoc.isBlank() || fs.textHash != textHash) {
            onStep("写转写稿")
            if (fs.transcriptDoc.isBlank()) {
                val d = Feishu.createDoc("$title · 转写稿", folder)
                runCatching { Feishu.transfer(d, "docx", stayPut = true) }
                fs = fs.copy(transcriptDoc = d)
            } else {
                runCatching { Feishu.setDocTitle(fs.transcriptDoc, "$title · 转写稿") }
                Feishu.clearDoc(fs.transcriptDoc)
            }
            Feishu.appendMarkdown(fs.transcriptDoc, meta(r0) + "\n\n" + transcriptMd(r0, sents))
            fs = fs.copy(textHash = textHash)
            Store.update(id) { it.copy(fs = fs) }
        }
        if (fs.audio.isBlank()) {
            onStep("传录音")
            val a = Store.audio(r0)
            if (a.exists() && a.length() > 0) {
                val safe = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(60)
                runCatching {
                    val t = Feishu.upload(a, "$safe.${a.extension.ifBlank { "aac" }}", folder)
                    runCatching { Feishu.transfer(t, "file", stayPut = true) }
                    fs = fs.copy(audio = t)
                    Store.update(id) { it.copy(fs = fs) }
                }
            }
        }

        onStep("写会议库")
        val r = Store.get(id) ?: r0
        val people = r.speakers.sortedByDescending { it.talkMs }.joinToString("、") { Store.speakerName(it) }
        val meetFields = JSONObject()
            .put("会名", title)
            .put("开始", r.startedAt)
            .put("时长（分钟）", (r.durationMs + 30_000) / 60_000)
            .put("分类", r.folder ?: UNFILED)
            .put("参会人", people)
            .put("一句话", r.brief)
            .put("听会编号", r.id)
            .put("发到的群", fs.sharedTo.joinToString(","))
        link(fs.minutesUrl, "纪要")?.let { meetFields.put("纪要", it) }
        link(fs.transcriptUrl, "转写稿")?.let { meetFields.put("转写稿", it) }
        link(fs.audioUrl, "录音")?.let { meetFields.put("录音", it) }
        val meetRow = if (fs.meetRow.isNotBlank()) {
            runCatching { Feishu.updateRecord(sp.base, sp.tMeet, fs.meetRow, meetFields); fs.meetRow }
                .getOrElse { Feishu.addRecords(sp.base, sp.tMeet, listOf(meetFields)).first() }
        } else Feishu.addRecords(sp.base, sp.tMeet, listOf(meetFields)).first()
        fs = fs.copy(meetRow = meetRow)

        // 待办和结论：整场重写（行少，简单可靠）
        Feishu.deleteRecords(sp.base, sp.tTodo, fs.todoRows)
        Feishu.deleteRecords(sp.base, sp.tPoint, fs.pointRows)
        val todoRows = Feishu.addRecords(sp.base, sp.tTodo, r.todos.map { t ->
            JSONObject()
                .put("事项", t.text).put("负责人", t.owner).put("是我的", t.mine)
                .put("状态", if (t.done) "做完了" else "没做完")
                .put("会议", JSONArray().put(meetRow))
                .put("会上时间", if (t.at >= 0) clock(t.at) else "")
                .put("听会编号", "${r.id}#${t.key}")
                .apply {
                    if (t.due > 0) put("截止", t.due)
                    link(t.taskUrl, "打开")?.let { put("飞书任务", it) }
                }
        })
        val points = Todos.points(Store.minutes(r))
        val pointRows = Feishu.addRecords(sp.base, sp.tPoint, points.map { p ->
            JSONObject()
                .put("内容", p.text).put("类型", if (p.open) "没定" else "已定")
                .put("会议", JSONArray().put(meetRow))
                .put("日期", r.startedAt)
                .put("分类", r.folder ?: UNFILED)
                .put("会上时间", if (p.at >= 0) clock(p.at) else "")
                .put("听会编号", r.id)
        })
        fs = fs.copy(todoRows = todoRows, pointRows = pointRows, syncedAt = System.currentTimeMillis(), error = "")
        Store.update(id) { it.copy(fs = fs) }
        return fs
    }

    /** 纪要发到了这个群：记下来，会议库里也写上 */
    fun markShared(recId: String, chatId: String) {
        val r = Store.update(recId) { it.copy(fs = it.fs.copy(sharedTo = (it.fs.sharedTo + chatId).distinct())) } ?: return
        val sp = Prefs.space.value
        if (sp.ready && r.fs.meetRow.isNotBlank())
            runCatching { Feishu.updateRecord(sp.base, sp.tMeet, r.fs.meetRow, JSONObject().put("发到的群", r.fs.sharedTo.joinToString(","))) }
    }

    /** 待办表里的一行跟着改（做完了、建了任务） */
    fun updateTodoRow(r: Rec, t: Todo) {
        val sp = Prefs.space.value
        if (!sp.ready) return
        val i = r.todos.indexOfFirst { it.key == t.key }
        val row = r.fs.todoRows.getOrNull(i) ?: return
        val f = JSONObject().put("状态", if (t.done) "做完了" else "没做完")
        link(t.taskUrl, "打开")?.let { f.put("飞书任务", it) }
        runCatching { Feishu.updateRecord(sp.base, sp.tTodo, row, f) }
    }

    /** 任务清单「会议待办」：第一次用时建 */
    @Synchronized
    fun taskList(): String? = tasklist()

    @Synchronized
    private fun tasklist(): String? {
        val sp = Prefs.space.value
        if (sp.tasklist.isNotBlank()) return sp.tasklist
        return runCatching { Feishu.createTasklist("会议待办") }.getOrNull()?.also { Prefs.setSpace(Prefs.space.value.copy(tasklist = it)) }
    }

    /** 这几条待办建成飞书任务，负责人是你；建过的跳过。返回建了几条 */
    fun createTasks(recId: String, keys: Collection<String>): Int {
        val r = Store.get(recId) ?: return 0
        val list = tasklist()
        val date = SimpleDateFormat("M月d日", Locale.CHINA).format(r.startedAt)
        var n = 0
        for (t in r.todos.filter { it.key in keys && it.task.isBlank() }) {
            val desc = buildString {
                append("$date「${r.title}」上说的")
                if (t.at >= 0) append("，会上 ${clock(t.at)}")
                if (t.owner.isNotBlank() && !t.mine) append("。负责人：${t.owner}")
                if (r.fs.minutesUrl.isNotBlank()) append("\n纪要：${r.fs.minutesUrl}")
            }
            val made = Feishu.createTask(t.text, desc, t.due, list)
            n++
            val nt = t.copy(task = made.guid, taskUrl = made.url)
            Store.update(recId) { cur -> cur.copy(todos = cur.todos.map { if (it.key == t.key) nt else it }) }
            Store.get(recId)?.let { updateTodoRow(it, nt) }
        }
        return n
    }

    /** 在飞书里勾完成的任务，回来同步成做完了 */
    fun pullTaskStatus() {
        Store.recs.value.filter { r -> r.todos.any { it.task.isNotBlank() } }.forEach { r ->
            r.todos.filter { it.task.isNotBlank() }.forEach { t ->
                val done = Feishu.taskDone(t.task) ?: return@forEach
                if (done != t.done) {
                    val nt = t.copy(done = done)
                    Store.update(r.id) { cur -> cur.copy(todos = cur.todos.map { if (it.key == t.key) nt else it }) }
                    Store.get(r.id)?.let { updateTodoRow(it, nt) }
                }
            }
        }
    }

    /** App 里勾了完成：飞书任务、会议库一起改 */
    fun setDone(recId: String, key: String, done: Boolean) {
        val r = Store.update(recId) { cur -> cur.copy(todos = cur.todos.map { if (it.key == key) it.copy(done = done) else it }) } ?: return
        val t = r.todos.firstOrNull { it.key == key } ?: return
        if (t.task.isNotBlank()) runCatching { Feishu.completeTask(t.task, done) }
        updateTodoRow(r, t)
    }
}
