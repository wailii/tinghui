package com.xiaodeng.tinghui

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** 阿里百炼录音文件转写：临时上传 → 提交 → 查进度 → 下载结果 */
object Dashscope {
    const val MODEL = "qwen-audio-3.1-asr-flash-filetrans"
    private const val BASE = "https://dashscope.aliyuncs.com/api/v1"

    class ApiError(msg: String) : IOException(msg)

    private fun key() = Prefs.apiKey.value.also { if (it.isBlank()) throw ApiError("还没填阿里百炼的 API Key") }

    private fun open(url: String, method: String, headers: Map<String, String> = emptyMap()): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 120_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun HttpURLConnection.body(): String {
        val code = responseCode
        val s = (if (code in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(s).optString("message") }.getOrNull()?.ifBlank { null } ?: s.take(200)
            throw ApiError(
                when (code) {
                    401 -> "这个 API Key 用不了：可能填错了，或者不是北京地域的"
                    403 -> "这个 API Key 没开通语音识别权限"
                    429 -> "请求太频繁，过一会儿再试"
                    else -> "阿里云返回错误（$code）：$msg"
                }
            )
        }
        return s
    }

    /** 试一下 key 能不能用：要一张上传凭证，不花钱 */
    fun check(k: String) {
        open("$BASE/uploads?action=getPolicy&model=${URLEncoder.encode(MODEL, "UTF-8")}", "GET", mapOf("Authorization" to "Bearer $k")).body()
    }

    /** 上传到百炼的免费临时存储（48 小时有效），返回 oss:// 地址 */
    fun upload(f: File, onProgress: (Float) -> Unit = {}): String {
        val auth = mapOf("Authorization" to "Bearer ${key()}")
        val p = JSONObject(
            open("$BASE/uploads?action=getPolicy&model=${URLEncoder.encode(MODEL, "UTF-8")}", "GET", auth).body()
        ).getJSONObject("data")
        val objectKey = "${p.getString("upload_dir")}/${f.name}"
        val boundary = UUID.randomUUID().toString().replace("-", "")
        val fields = linkedMapOf(
            "OSSAccessKeyId" to p.getString("oss_access_key_id"),
            "Signature" to p.getString("signature"),
            "policy" to p.getString("policy"),
            "x-oss-object-acl" to p.getString("x_oss_object_acl"),
            "x-oss-forbid-overwrite" to p.getString("x_oss_forbid_overwrite"),
            "key" to objectKey,
            "success_action_status" to "200",
        )
        val head = buildString {
            fields.forEach { (k, v) -> append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${f.name}\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = open(p.getString("upload_host"), "POST", mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"))
        c.doOutput = true
        c.readTimeout = 600_000
        c.setFixedLengthStreamingMode(head.size + f.length() + tail.size)
        c.outputStream.use { out ->
            out.write(head)
            f.inputStream().use { inp ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    onProgress(sent.toFloat() / f.length())
                }
            }
            out.write(tail)
        }
        if (c.responseCode != 200) throw ApiError("上传失败（${c.responseCode}）")
        c.disconnect()
        return "oss://$objectKey"
    }

    /** 提交转写，返回 task_id */
    fun submit(fileUrl: String, hotwords: List<String>): String {
        val params = JSONObject()
            .put("diarization_enabled", true)
            .put("language_hints", JSONArray(listOf("zh", "en")))
            .put("channel_id", JSONArray(listOf(0)))
        if (hotwords.isNotEmpty()) {
            params.put("vocabulary", JSONObject().apply { hotwords.forEach { put(it, 4) } })
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("input", JSONObject().put("file_urls", JSONArray(listOf(fileUrl))))
            .put("parameters", params)
        val c = open(
            "$BASE/services/audio/asr/transcription", "POST",
            mapOf(
                "Authorization" to "Bearer ${key()}",
                "Content-Type" to "application/json",
                "X-DashScope-Async" to "enable",
                "X-DashScope-OssResourceResolve" to "enable",
            ),
        )
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return JSONObject(c.body()).getJSONObject("output").getString("task_id")
    }

    data class TaskState(
        val status: String,
        val resultUrl: String? = null,
        val error: String? = null,
        val inTokens: Long = 0,
        val outTokens: Long = 0,
    )

    fun task(taskId: String): TaskState {
        val o = JSONObject(open("$BASE/tasks/$taskId", "GET", mapOf("Authorization" to "Bearer ${key()}")).body())
        val out = o.getJSONObject("output")
        val usage = o.optJSONObject("usage")
        val res = out.optJSONArray("results").objs().firstOrNull()
        val err = res?.optStr("message") ?: out.optStr("message") ?: res?.optStr("code") ?: out.optStr("code")
        return TaskState(
            status = out.optString("task_status"),
            resultUrl = res?.optStr("transcription_url"),
            error = err,
            inTokens = usage?.optLong("input_tokens") ?: 0,
            outTokens = usage?.optLong("output_tokens") ?: 0,
        )
    }

    /** 下载转写结果：(开始毫秒, 结束毫秒, 说话人编号, 文本) */
    fun result(url: String): List<Sentence> {
        val o = JSONObject(open(url, "GET").body())
        val t = o.optJSONArray("transcripts").objs().firstOrNull() ?: return emptyList()
        return t.optJSONArray("sentences").objs().map {
            Sentence(it.optLong("begin_time"), it.optLong("end_time"), it.optInt("speaker_id", 0), it.optString("text"))
        }
    }
}
