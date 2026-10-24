package com.xiaodeng.tinghui

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/**
 * 声纹库 + 认人。声纹只存在手机里（files/voices.json），不上传。
 * 模型：3D-Speaker CAM++ 中文版，经 sherpa-onnx 在手机上跑。
 */
object Voice {
    /** 相似度够这个、且比第二像的人高出 MARGIN，就直接写名字 */
    const val AUTO = 0.72f
    const val MARGIN = 0.08f
    /** 够这个就打问号让你确认 */
    const val GUESS = 0.62f
    /** 长会切段后，不同段里的说话人像到这个程度算同一个人 */
    const val MERGE = 0.70f
    /** 用这台手机录音的人场场都在：这场里只有一个说话人明显像他，够这个就认成他 */
    const val ME_AUTO = 0.64f
    const val ME_GAP = 0.10f
    private const val MAX_SAMPLES = 30
    private const val PICKS_PER_SPEAKER = 10
    private const val MIN_PICK_MS = 1500L
    private const val MAX_PICK_MS = 8000L

    data class Person(val id: String, val name: String, val samples: List<FloatArray>, val updatedAt: Long)

    private lateinit var file: File
    private val _people = MutableStateFlow<List<Person>>(emptyList())
    val people: StateFlow<List<Person>> = _people

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "voices.json")
        _people.value = runCatching {
            JSONArray(file.readText()).objs().map { o ->
                Person(
                    o.getString("id"), o.getString("name"),
                    o.optJSONArray("samples").let { a ->
                        if (a == null) emptyList() else (0 until a.length()).map { i ->
                            val s = a.getJSONArray(i)
                            FloatArray(s.length()) { k -> s.getDouble(k).toFloat() }
                        }
                    },
                    o.optLong("updatedAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val a = JSONArray(_people.value.map { p ->
            JSONObject().put("id", p.id).put("name", p.name).put("updatedAt", p.updatedAt)
                .put("samples", JSONArray(p.samples.map { s -> JSONArray(s.map { it.toDouble() }) }))
        })
        val tmp = File(file.parentFile, "voices.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    fun name(id: String) = _people.value.firstOrNull { it.id == id }?.name

    @Synchronized
    fun create(name: String): String {
        _people.value.firstOrNull { it.name == name }?.let { return it.id }
        val p = Person(UUID.randomUUID().toString().take(8), name, emptyList(), System.currentTimeMillis())
        _people.value = _people.value + p
        save()
        return p.id
    }

    @Synchronized
    fun enroll(id: String, emb: FloatArray) {
        _people.value = _people.value.map {
            if (it.id == id) it.copy(samples = (it.samples + emb).takeLast(MAX_SAMPLES), updatedAt = System.currentTimeMillis()) else it
        }
        save()
    }

    /** 撤掉存错的一份声音 */
    @Synchronized
    fun unenroll(id: String, emb: FloatArray) {
        _people.value = _people.value.map { p ->
            if (p.id == id) p.copy(samples = p.samples.filterNot { it.contentEquals(emb) }) else p
        }
        save()
    }

    @Synchronized
    fun rename(id: String, name: String) {
        _people.value = _people.value.map { if (it.id == id) it.copy(name = name) else it }
        save()
    }

    @Synchronized
    fun delete(id: String) {
        _people.value = _people.value.filter { it.id != id }
        save()
    }

    // ---------- 声纹模型 ----------

    private var extractor: SpeakerEmbeddingExtractor? = null

    @Synchronized
    private fun extractor(ctx: Context): SpeakerEmbeddingExtractor = extractor ?: SpeakerEmbeddingExtractor(
        ctx.assets,
        SpeakerEmbeddingExtractorConfig(model = "campplus_zh.onnx", numThreads = 2, debug = false, provider = "cpu"),
    ).also { extractor = it }

    @Synchronized
    fun embed(ctx: Context, pcm: FloatArray): FloatArray? {
        val ex = extractor(ctx)
        val s = ex.createStream()
        try {
            s.acceptWaveform(pcm, 16000)
            s.inputFinished()
            if (!ex.isReady(s)) return null
            return normalize(ex.compute(s))
        } finally {
            s.release()
        }
    }

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val d = sqrt(n).toFloat().takeIf { it > 0 } ?: return v
        return FloatArray(v.size) { v[it] / d }
    }

    fun cos(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun mean(list: List<FloatArray>): FloatArray? {
        if (list.isEmpty()) return null
        val m = FloatArray(list[0].size)
        list.forEach { v -> for (i in m.indices) m[i] += v[i] }
        return normalize(m)
    }

    /** 和库里某个人有多像：跟他存过的每份样本比，取最像的 */
    fun similarity(emb: FloatArray, p: Person): Float = p.samples.maxOfOrNull { cos(emb, it) } ?: -1f

    /** 用这台手机录音的人：名字和飞书里的自己一样的；没有就是出现在最多场会里的（至少 2 场） */
    fun me(): Person? {
        val list = _people.value.filter { it.samples.isNotEmpty() }
        list.firstOrNull { BuildConfig.FEISHU_USER_NAME.isNotBlank() && it.name == BuildConfig.FEISHU_USER_NAME }?.let { return it }
        val n = HashMap<String, Int>()
        Store.recs.value.forEach { r -> r.speakers.mapNotNull { it.personId }.toSet().forEach { n[it] = (n[it] ?: 0) + 1 } }
        val top = n.entries.filter { e -> list.any { it.id == e.key } }.maxByOrNull { it.value } ?: return null
        return if (top.value >= 2) list.first { it.id == top.key } else null
    }

    class Match(val person: Person?, val s1: Float, val s2: Float) {
        val sure get() = person != null && s1 >= AUTO && s1 - s2 >= MARGIN
    }

    fun match(e: FloatArray?, people: List<Person>): Match {
        if (e == null || people.isEmpty()) return Match(null, 0f, 0f)
        val ranked = people.map { it to similarity(e, it) }.sortedByDescending { it.second }
        return Match(ranked[0].first, ranked[0].second, ranked.getOrNull(1)?.second ?: 0f)
    }

    /** 这场会里哪个说话人是手机主人：最像他的那个够像，而且比第二像的说话人高出一截 */
    fun pickMe(embs: Map<Int, FloatArray>, free: Set<Int>, me: Person): Int? {
        val ranked = embs.map { (k, e) -> k to similarity(e, me) }.sortedByDescending { it.second }
        val top = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0f
        return top.first.takeIf { it in free && top.second >= ME_AUTO && top.second - second >= ME_GAP }
    }

    class Analysis(val mapping: Map<Int, Int>, val speakers: List<Speaker>)

    /**
     * sentences 的 spk 是「段号×100+阿里给的说话人号」。
     * 1. 每个说话人挑最长的几句，剪出音频算声纹，取平均
     * 2. 长会切过段的：不同段里声纹像的，合成同一个人
     * 3. 和声纹库比，认出是谁
     */
    fun analyze(
        ctx: Context,
        audio: File,
        sentences: List<Sentence>,
        chunkOf: Map<Int, Int> = emptyMap(),
        withVoice: Boolean = true,
    ): Analysis {
        val byKey = sentences.groupBy { it.spk }
        val firstAt = byKey.mapValues { (_, v) -> v.minOf { it.begin } }
        val talk = byKey.mapValues { (_, v) -> v.sumOf { it.end - it.begin } }

        val cent = HashMap<Int, FloatArray>()
        if (withVoice) {
            val picks = byKey.flatMap { (k, v) ->
                v.filter { it.end - it.begin >= MIN_PICK_MS }
                    .sortedByDescending { it.end - it.begin }
                    .take(PICKS_PER_SPEAKER)
                    .map {
                        val d = it.end - it.begin
                        if (d <= MAX_PICK_MS) Triple(k, it.begin, it.end)
                        else (it.begin + it.end).div(2).let { mid -> Triple(k, mid - MAX_PICK_MS / 2, mid + MAX_PICK_MS / 2) }
                    }
            }.sortedBy { it.second }
            // 时间段不能重叠
            val clean = ArrayList<Triple<Int, Long, Long>>()
            for (p in picks) if (clean.isEmpty() || p.second >= clean.last().third) clean += p
            val embs = HashMap<Int, MutableList<FloatArray>>()
            AudioDecode.collect(audio, clean.map { longArrayOf(it.second, it.third) }) { i, pcm ->
                embed(ctx, pcm)?.let { embs.getOrPut(clean[i].first) { mutableListOf() } += it }
            }
            embs.forEach { (k, v) -> mean(v)?.let { cent[k] = it } }
        }

        // 合段：段 0 的每个说话人各算一个人；后面段的说话人找前面最像的、且同一段里没被占的
        class G(val keys: MutableList<Int>, var emb: FloatArray?, var w: Int)
        val groups = ArrayList<G>()
        fun chunk(k: Int) = chunkOf[k] ?: 0
        for (k in byKey.keys.sortedWith(compareBy({ chunk(it) }, { firstAt[it] }))) {
            val e = cent[k]
            val chunk = chunk(k)
            val best = if (e == null || chunk == 0) null else groups
                .filter { g -> g.emb != null && g.keys.none { chunk(it) == chunk } }
                .maxByOrNull { cos(e, it.emb!!) }
                ?.takeIf { cos(e, it.emb!!) >= MERGE }
            if (best != null) {
                best.keys += k
                best.emb = mean(listOf(best.emb!!, e!!))
            } else groups += G(mutableListOf(k), e, 1)
        }
        groups.sortBy { g -> g.keys.minOf { firstAt[it]!! } }
        val mapping = HashMap<Int, Int>()
        groups.forEachIndexed { idx, g -> g.keys.forEach { mapping[it] = idx } }

        // 认人
        val people = _people.value.filter { it.samples.isNotEmpty() }
        val cands = groups.mapIndexed { idx, g -> idx to match(g.emb, people) }
        val taken = HashSet<String>()
        val result = HashMap<Int, Speaker>()
        for ((idx, m) in cands.sortedByDescending { it.second.s1 }) {
            val g = groups[idx]
            val base = Speaker(idx = idx, talkMs = g.keys.sumOf { talk[it] ?: 0 }, emb = g.emb, score = m.s1, chunk = chunk(g.keys.first()))
            result[idx] = when {
                m.sure && m.person!!.id !in taken -> {
                    taken += m.person.id
                    base.copy(personId = m.person.id, auto = true)
                }
                m.person != null && m.s1 >= GUESS -> base.copy(guessId = m.person.id)
                else -> base
            }
        }
        val me = me()
        if (me != null && me.id !in taken) {
            val embs = groups.withIndex().mapNotNull { (i, g) -> g.emb?.let { i to it } }.toMap()
            pickMe(embs, result.filterValues { it.personId == null }.keys, me)?.let { i ->
                result[i] = result[i]!!.copy(personId = me.id, auto = true, guessId = null)
            }
        }
        return Analysis(mapping, (0 until groups.size).map { result[it]!! })
    }
}
