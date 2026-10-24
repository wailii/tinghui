package com.xiaodeng.tinghui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import kotlin.math.min

/** 一场录音从「录完」到「出稿」：切段 → 上传 → 转写 → 合段 → 认人。每步都存盘，断了能接着来 */
object Pipeline {
    /** 开了区分说话人，阿里建议单个文件不超过 2 小时；超过 115 分钟的切成每段不超过 100 分钟 */
    private const val SINGLE_MAX_MS = 115 * 60_000L
    private const val CHUNK_MS = 100 * 60_000L
    private const val OSS_TTL_MS = 40 * 3600_000L

    private fun setStatus(id: String, s: Status) = Store.update(id) { it.copy(status = s, error = null) }

    private fun saveChunk(id: String, i: Int, c: Chunk) = Store.update(id) { r ->
        r.copy(chunks = r.chunks.toMutableList().also { it[i] = c })
    }

    suspend fun process(ctx: Context, id: String, step: (String) -> Unit) = withContext(Dispatchers.IO) {
        var r = Store.get(id) ?: return@withContext
        val dir = Store.dir(id)
        val audio = Store.audio(r)
        if (!audio.exists()) throw Dashscope.ApiError("录音文件不见了")

        if (r.chunks.isEmpty()) {
            val chunks = if (Adts.isAdts(audio) && r.durationMs > SINGLE_MAX_MS) {
                step("切段中")
                Adts.split(audio, CHUNK_MS).map { Chunk(it.first, it.second, it.third) }
            } else listOf(Chunk(audio.name, 0, r.durationMs))
            r = Store.update(id) { it.copy(chunks = chunks) }!!
        }

        val n = r.chunks.size
        for (i in 0 until n) {
            var c = Store.get(id)!!.chunks[i]
            if (c.done) continue
            val part = if (n > 1) "（第 ${i + 1}/$n 段）" else ""
            if (c.taskId == null) {
                if (c.ossUrl == null || System.currentTimeMillis() - c.ossAt > OSS_TTL_MS) {
                    setStatus(id, Status.Uploading)
                    step("上传中$part")
                    var last = -1
                    val url = Dashscope.upload(File(dir, c.file)) { p ->
                        val pct = (p * 100).toInt()
                        if (pct != last && pct % 5 == 0) {
                            last = pct
                            step("上传中 $pct%$part")
                        }
                    }
                    c = c.copy(ossUrl = url, ossAt = System.currentTimeMillis())
                    saveChunk(id, i, c)
                }
                setStatus(id, Status.Transcribing)
                step("转写中$part")
                c = c.copy(taskId = Dashscope.submit(c.ossUrl!!, Prefs.hotwordList(r.hot)))
                saveChunk(id, i, c)
            }
            setStatus(id, Status.Transcribing)
            var wait = 3000L
            val t0 = System.currentTimeMillis()
            while (true) {
                val st = Dashscope.task(c.taskId!!)
                if (st.status == "FAILED" && st.error?.contains("HAVE_NO_WORDS") == true) {
                    // 整段没人说话：不是失败，出一份空稿
                    File(dir, "chunk_$i.json").writeText("[]")
                    c = c.copy(done = true)
                    saveChunk(id, i, c)
                    break
                }
                when (st.status) {
                    "SUCCEEDED" -> {
                        val url = st.resultUrl ?: throw Dashscope.ApiError("转写完成但没拿到结果")
                        val list = Dashscope.result(url).map {
                            it.copy(begin = it.begin + c.offsetMs, end = it.end + c.offsetMs, spk = i * 100 + it.spk)
                        }
                        File(dir, "chunk_$i.json").writeText(sentencesToJson(list).toString())
                        c = c.copy(done = true)
                        Store.update(id) { rec ->
                            rec.copy(
                                chunks = rec.chunks.toMutableList().also { it[i] = c },
                                inTokens = rec.inTokens + st.inTokens,
                                outTokens = rec.outTokens + st.outTokens,
                            )
                        }
                        break
                    }
                    "FAILED", "CANCELED", "UNKNOWN" -> {
                        // 下次重试从上传开始（临时文件可能过期了）
                        saveChunk(id, i, c.copy(taskId = null, ossUrl = null))
                        throw Dashscope.ApiError("转写失败：${st.error ?: st.status}")
                    }
                    else -> {
                        val sec = (System.currentTimeMillis() - t0) / 1000
                        step("转写中$part · ${sec / 60}:%02d".format(sec % 60))
                        delay(wait)
                        wait = min((wait * 1.3).toLong(), 10_000L)
                    }
                }
            }
        }

        // 先出稿：说话人按第一次开口的先后编号。认熟人、长会合段放到后台做，不让你等
        r = Store.get(id)!!
        val all = r.chunks.indices.flatMap { i ->
            sentencesFromJson(JSONArray(File(dir, "chunk_$i.json").readText()))
        }.sortedBy { it.begin }
        val firstAt = all.groupBy { it.spk }.mapValues { (_, v) -> v.minOf { it.begin } }
        val keys = firstAt.entries.sortedBy { it.value }.map { it.key }
        val idxOf = keys.withIndex().associate { (i, k) -> k to i }
        val sents = all.map { it.copy(spk = idxOf[it.spk]!!) }
        val talk = sents.groupBy { it.spk }.mapValues { (_, v) -> v.sumOf { it.end - it.begin } }
        Store.saveSentences(id, sents)
        val llm = Prefs.llm.value
        Store.update(id) {
            it.copy(
                status = Status.Done, error = null, sentenceCount = sents.size,
                preview = sents.joinToString("") { s -> s.text }.take(90),
                speakers = keys.indices.map { i -> Speaker(idx = i, talkMs = talk[i] ?: 0, chunk = keys[i] / 100) },
                voiceDone = sents.isEmpty(),
                // 配了大模型就排队出纪要（等后台认完人再出，纪要里就是真名）
                sumState = if (llm.ready && llm.auto && sents.isNotEmpty()) SumState.Queued else it.sumState,
            )
        }
    }

    /**
     * 后台认人：算每个说话人的声纹，认出声纹库里的熟人自动写上名字，长会把不同段里的同一个人合起来。
     * 你在这之前手动起的名字照留，顺便把声音存进声纹库。
     */
    suspend fun voice(ctx: Context, id: String) = withContext(Dispatchers.IO) {
        try {
            val r = Store.get(id) ?: return@withContext
            val an = Voice.analyze(ctx, Store.audio(r), Store.sentences(id), r.speakers.associate { it.idx to it.chunk })
            val identity = an.mapping.all { (k, v) -> k == v }
            if (!identity) Store.saveSentences(id, Store.sentences(id).map { it.copy(spk = an.mapping[it.spk] ?: it.spk) })
            val alive = Store.sentences(id).map { it.spk }.toSet()
            val toEnroll = ArrayList<Pair<String, FloatArray>>()
            // 用最新的说话人列表算：认人期间你改的名字、做的合并都不能被覆盖
            Store.update(id) { cur ->
                val speakers = an.speakers.filter { it.idx in alive }.map { ns ->
                    val manual = cur.speakers.firstOrNull { an.mapping[it.idx] == ns.idx && it.personId != null && !it.auto }
                    if (manual != null) {
                        val e = ns.emb
                        if (e != null && manual.enrolled == null) toEnroll += manual.personId!! to e
                        ns.copy(personId = manual.personId, auto = false, guessId = null, enrolled = manual.enrolled ?: manual.personId.takeIf { e != null })
                    } else ns
                }
                // 手动起的名字优先：自动认成同一个人的，退成「像谁？」
                val manualIds = speakers.filter { it.personId != null && !it.auto }.map { it.personId }.toSet()
                cur.copy(
                    speakers = speakers.map {
                        if (it.auto && it.personId in manualIds) it.copy(personId = null, auto = false, guessId = it.personId) else it
                    },
                    voiceDone = true,
                )
            }
            toEnroll.forEach { (pid, e) -> Voice.enroll(pid, e) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 认人出问题不影响看稿，跳过
        } finally {
            if (Store.get(id)?.voiceDone == false) Store.update(id) { it.copy(voiceDone = true) }
        }
    }

    /** 网络类错误：不算失败，等网好了接着来 */
    fun isNetwork(e: Throwable) = e is IOException && e !is Dashscope.ApiError
}
