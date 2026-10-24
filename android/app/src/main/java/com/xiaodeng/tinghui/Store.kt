package com.xiaodeng.tinghui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 每场录音一个目录：recs/<id>/meta.json、audio.aac、transcript.json、chunk_N.json */
object Store {
    private lateinit var root: File
    private val lock = Any()
    private val _recs = MutableStateFlow<List<Rec>>(emptyList())
    val recs: StateFlow<List<Rec>> = _recs

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "recs").apply { mkdirs() }
        val list = root.listFiles().orEmpty().mapNotNull { d ->
            runCatching { Rec.fromJson(JSONObject(File(d, "meta.json").readText())) }.getOrNull()
        }
        _recs.value = list.sortedByDescending { it.startedAt }
    }

    fun dir(id: String) = File(root, id)
    fun audio(r: Rec) = File(dir(r.id), r.audio)
    fun get(id: String) = _recs.value.firstOrNull { it.id == id }

    fun put(r: Rec) = synchronized(lock) {
        dir(r.id).mkdirs()
        val f = File(dir(r.id), "meta.json")
        val tmp = File(dir(r.id), "meta.json.tmp")
        tmp.writeText(r.toJson().toString())
        tmp.renameTo(f)
        _recs.value = (_recs.value.filter { it.id != r.id } + r).sortedByDescending { it.startedAt }
        r
    }

    /** 改一场；f 原样返回就不写盘 */
    fun update(id: String, f: (Rec) -> Rec): Rec? = synchronized(lock) {
        val r = get(id) ?: return null
        val n = f(r)
        if (n === r) r else put(n)
    }

    fun delete(id: String) = synchronized(lock) {
        dir(id).deleteRecursively()
        _recs.value = _recs.value.filter { it.id != id }
    }

    fun sentences(id: String): List<Sentence> =
        runCatching { sentencesFromJson(JSONArray(File(dir(id), "transcript.json").readText())) }.getOrDefault(emptyList())

    fun saveSentences(id: String, list: List<Sentence>) {
        val tmp = File(dir(id), "transcript.json.tmp")
        tmp.writeText(sentencesToJson(list).toString())
        tmp.renameTo(File(dir(id), "transcript.json"))
    }

    /** 下一场要转写的（最早的先来） */
    fun nextPending(): Rec? = _recs.value.filter { it.busy }.minByOrNull { it.startedAt }

    fun newId(now: Long): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(now)

    /** 「10月20日 下午3:04」 */
    fun defaultTitle(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val part = when (h) {
            in 0..5 -> "凌晨"
            in 6..11 -> "上午"
            12 -> "中午"
            in 13..17 -> "下午"
            else -> "晚上"
        }
        val h12 = if (h > 12) h - 12 else h
        return "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 $part$h12:%02d".format(c.get(Calendar.MINUTE))
    }

    /** 说话人在界面上的名字 */
    fun speakerName(s: Speaker): String =
        s.personId?.let { Voice.name(it) } ?: "说话人 ${s.idx + 1}"

    fun guessName(s: Speaker): String? = if (!known(s)) s.guessId?.let { Voice.name(it) } else null

    /** 认出来是谁了（库里还有这个人） */
    fun known(s: Speaker) = s.personId != null && Voice.name(s.personId) != null

    /**
     * 给某个说话人起名：认定成声纹库里的某人（没有就新建），把这场会的声纹存进去。
     * 同一场会里已经有人叫这个名字，说明转写把一个人拆成了两个，合并成一个。
     */
    fun nameSpeaker(recId: String, idx: Int, personId: String?, newName: String?) {
        val r = get(recId) ?: return
        val sp = r.speakers.firstOrNull { it.idx == idx } ?: return
        val pid = personId ?: Voice.create(newName!!.trim())
        // 之前存错到别人名下的声音先撤掉，再存到这个人名下
        sp.emb?.let { e ->
            if (sp.enrolled != null && sp.enrolled != pid) Voice.unenroll(sp.enrolled, e)
            if (sp.enrolled != pid) Voice.enroll(pid, e)
        }
        val enrolled = if (sp.emb != null) pid else null
        // 用最新的这场改：后台认人、出纪要期间写进去的别覆盖掉
        update(recId) { cur ->
            val me = cur.speakers.firstOrNull { it.idx == idx } ?: return@update cur
            val twin = cur.speakers.firstOrNull { it.idx != idx && it.personId == pid }
            if (twin != null) {
                val merged = cur.speakers.filter { it.idx != idx }.map {
                    if (it.idx == twin.idx) it.copy(auto = false, guessId = null, talkMs = it.talkMs + me.talkMs) else it
                }
                saveSentences(recId, sentences(recId).map { if (it.spk == idx) it.copy(spk = twin.idx) else it })
                cur.copy(speakers = merged, alias = cur.alias.mapValues { (_, v) -> if (v == idx) twin.idx else v } + (idx to twin.idx))
            } else cur.copy(speakers = cur.speakers.map {
                if (it.idx == idx) it.copy(personId = pid, auto = false, guessId = null, enrolled = enrolled, notId = null) else it
            })
        }
        refreshTodos(recId)
        if (sp.emb != null) kotlin.concurrent.thread { runCatching { rematch() } }
    }

    /**
     * 声纹库多了谁的声音，回头把各场里还没名字的说话人再对一遍。
     * 用每场存着的声纹比，不用重新听录音；你说过「不是他」的不再认成他
     */
    fun rematch() {
        val people = Voice.people.value.filter { it.samples.isNotEmpty() }
        if (people.isEmpty()) return
        val me = Voice.me()
        _recs.value.filter { r -> r.status == Status.Done && r.voiceDone && r.speakers.any { it.personId == null && it.emb != null } }.forEach { r ->
            update(r.id) { cur ->
                val taken = cur.speakers.mapNotNull { it.personId }.toHashSet()
                val next = HashMap<Int, Speaker>()
                cur.speakers.filter { it.personId == null && it.emb != null }
                    .map { sp -> sp to Voice.match(sp.emb, people.filter { !sp.rejects(it.id) }) }
                    .sortedByDescending { it.second.s1 }
                    .forEach { (sp, m) ->
                        val p = m.person ?: return@forEach
                        if (m.sure && p.id !in taken) {
                            taken += p.id
                            next[sp.idx] = sp.copy(personId = p.id, auto = true, guessId = null, score = m.s1)
                        } else if (sp.guessId == null && m.s1 >= Voice.GUESS) next[sp.idx] = sp.copy(guessId = p.id, score = m.s1)
                    }
                if (me != null && me.id !in taken) {
                    val free = cur.speakers.filter { it.personId == null && next[it.idx]?.personId == null && !it.rejects(me.id) }.map { it.idx }.toSet()
                    val embs = cur.speakers.mapNotNull { s -> s.emb?.let { s.idx to it } }.toMap()
                    Voice.pickMe(embs, free, me)?.let { i ->
                        next[i] = cur.speakers.first { it.idx == i }.copy(personId = me.id, auto = true, guessId = null)
                    }
                }
                if (next.isEmpty()) cur else cur.copy(speakers = cur.speakers.map { next[it.idx] ?: it })
            }
            refreshTodos(r.id)
        }
    }

    /** 声纹库里删掉一个人：所有录音里认成他、猜成他的都退回「说话人 N」 */
    fun forgetPerson(pid: String) = synchronized(lock) {
        _recs.value.filter { r -> r.speakers.any { it.personId == pid || it.guessId == pid } }.forEach { r ->
            put(r.copy(speakers = r.speakers.map {
                it.copy(
                    personId = it.personId.takeIf { p -> p != pid },
                    auto = if (it.personId == pid) false else it.auto,
                    guessId = it.guessId.takeIf { g -> g != pid },
                )
            }))
        }
    }

    /** 下一场要出纪要的 */
    fun nextSummary(): Rec? = _recs.value.filter { it.status == Status.Done && it.voiceDone && it.sumState == SumState.Queued }.minByOrNull { it.startedAt }

    /** 配好大模型后，之前没出过纪要的都排上队 */
    fun queueMissingSummaries(): Int = synchronized(lock) {
        val l = Prefs.llm.value
        if (!l.ready || !l.auto) return 0
        val todo = _recs.value.filter { it.status == Status.Done && it.sentenceCount > 0 && it.sumState == SumState.None }
        todo.forEach { put(it.copy(sumState = SumState.Queued)) }
        todo.size
    }

    /** 下一场要在后台认人的 */
    fun nextVoice(): Rec? = _recs.value.filter { it.status == Status.Done && !it.voiceDone }.minByOrNull { it.startedAt }

    /**
     * 显示用的纪要：出纪要时叫「说话人 2」的，现在起了名就换成名字。
     * 先把旧名字换成占位，再换新名字，避免两个人互换名字时串掉；「说话人 1」不会误伤「说话人 12」
     */
    fun minutes(r: Rec): String {
        var t = r.summary
        if (t.isBlank() || r.sumNames.isEmpty()) return t
        val now = r.sumNames.mapNotNull { (idx, old) ->
            val sp = r.speakers.firstOrNull { it.idx == idx } ?: r.alias[idx]?.let { a -> r.speakers.firstOrNull { it.idx == a } }
            val name = sp?.let { speakerName(it) } ?: return@mapNotNull null
            if (name != old) Triple(idx, old, name) else null
        }.sortedByDescending { it.second.length }
        // 模型常把「说话人 2」写成「说话人2」，两种都换
        now.forEach { (idx, old, _) ->
            val pat = Regex("^说话人 (\\d+)$").find(old)?.let { "说话人\\s?" + it.groupValues[1] } ?: Regex.escape(old)
            t = t.replace(Regex("$pat(?![0-9])"), "\u0001$idx\u0001")
        }
        now.forEach { (idx, _, name) -> t = t.replace("\u0001$idx\u0001", name) }
        return t
    }

    fun renameFolder(old: String, new: String) = synchronized(lock) {
        Prefs.setFolders(Prefs.folders.value.map { if (it.name == old) it.copy(name = new) else it })
        _recs.value.filter { it.folder == old }.forEach { put(it.copy(folder = new)) }
    }

    /** 删分类：里面的会议变成未分类，不删会议 */
    fun removeFolder(name: String) = synchronized(lock) {
        Prefs.setFolders(Prefs.folders.value.filter { it.name != name })
        _recs.value.filter { it.folder == name }.forEach { put(it.copy(folder = null, folderManual = false)) }
    }

    /** 纪要变了、说话人改名了：重新拆待办（做没做完、建没建任务照留） */
    /** 每场会的待办按纪要重抽一遍（老版本出的纪要当时没抽）；返回变了的会 */
    fun refreshAllTodos(): List<String> = recs.value.filter { it.status == Status.Done && it.summary.isNotBlank() }.mapNotNull { r ->
        val before = r.todos
        refreshTodos(r.id)
        if (get(r.id)?.todos != before) r.id else null
    }

    fun refreshTodos(id: String) {
        val r = get(id) ?: return
        if (r.summary.isBlank()) return
        val l = Todos.parse(minutes(r), r.startedAt, r.todos)
        if (l != r.todos) update(id) { it.copy(todos = l) }
    }

    /** 自动认出的名字认错了，退回成「说话人 N」 */
    fun clearSpeaker(recId: String, idx: Int) {
        val sp = get(recId)?.speakers?.firstOrNull { it.idx == idx }
        if (sp?.enrolled != null && sp.emb != null) Voice.unenroll(sp.enrolled, sp.emb)
        update(recId) { r ->
            r.copy(speakers = r.speakers.map {
                if (it.idx == idx) it.copy(
                    personId = null, auto = false, guessId = null, enrolled = null,
                    notId = listOfNotNull(it.notId, it.personId).joinToString(",").ifBlank { null },
                ) else it
            })
        }
        refreshTodos(recId)
    }
}
