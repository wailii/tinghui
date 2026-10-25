package com.xiaodeng.tinghui

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 出纪要、起标题、分类、问答：调你自己配的大模型（OpenAI 兼容的 /chat/completions）。
 * 除了 model 和 messages，只多传输出上限和关思考两个参数；接口不认（400）就去掉再试。
 */
object Llm {
    class LlmError(msg: String) : IOException(msg)

    private const val HEAD = """你是产品经理的会议记录员。读一场会议的转写稿（每行「[分:秒] 说话人：内容」），写一份同事拿到就能接着干活的纪要。

内容要实：
- 只写会上真说过的。数字、日期、人名、系统名、页面名、字段名照原话写全，不要概括成「相关数据」「若干问题」「多个方面」。
- 每条写清楚谁、对什么、说了什么或定了什么。会上说了原因、依据或反对意见，就跟在后面写上。
- 转写有错别字时按上下文改对，比如「盖览页」写成「概览页」。听不清、拿不准的写「（没听清）」，不要猜。
- 关键内容句末带一个时间，格式 [12:35]，用最早说到这件事的那一行的时间。一条只带一个，不要连着写好几个。

写法像人写的工作笔记：
- 短句，主谓宾写全。不用「深入讨论」「进一步明确」「相关」「等方面」「赋能」「抓手」「闭环」「对齐」这类空词。
- 不加粗，不用感叹号。不写「本次会议」「综上」「总的来说」「值得注意的是」。
- 不评价会开得好不好，不替读者下结论，不补会上没说的建议。
- 同一件事只写一次；表格里写了的，正文不再复述。

表格：
- 有 3 条以上同类信息要逐项列或对比，比如按人分的待办、几个方案的取舍、一组指标数字，用 Markdown 表格。整列都空的列不要放。
- 文字能说清的就不做表。"""

    private const val FORMAT = """直接按下面这个格式输出，不要先打草稿，前后不要加任何别的话：
标题：不超过 16 个字，说清这场会讲了什么，比如「资源概览页改版评审」。不带日期，不带「纪要」「会议」
分类：只写一个分类名。从「已有分类」里挑最合适的，原样写；都不合适才新起一个 2 到 6 个字的名字，要是能装下一类会议的大类，比如「资源管理」「招聘」「产品评审」
摘要：30 个字以内，一句话说这场会定了什么或推进到哪，直接说事，不用「本次会议」「讨论了」开头
---
纪要正文（Markdown：## 是一节，### 是小标题，- 是一条）"""

    private fun system(t: Prefs.Template) = "$HEAD\n\n${Chart.PROMPT}\n\n纪要正文这样写：\n${t.body.trim()}\n\n$FORMAT"

    private fun base(url: String) = url.trim().trimEnd('/').removeSuffix("/chat/completions")

    private fun open(url: String, key: String, method: String, readTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            this.readTimeout = readTimeout
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer ${key.trim()}")
        }

    private fun HttpURLConnection.read(): JSONObject {
        val code = responseCode
        val s = (if (code in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching {
                val o = JSONObject(s)
                o.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: o.optString("message")
            }.getOrNull()?.ifBlank { null } ?: s.take(160)
            throw LlmError(
                when (code) {
                    401, 403 -> "Key 不对或没权限（$code）：$msg"
                    404 -> "地址或模型名不对（404）：$msg"
                    429 -> "请求太多或额度用完了（429）：$msg"
                    else -> "大模型接口报错（$code）：$msg"
                }
            )
        }
        return runCatching { JSONObject(s) }.getOrElse { throw LlmError("接口返回的不是 JSON，地址可能填错了") }
    }

    /** 网络层的错误翻成人话 */
    private fun <T> net(url: String, block: () -> T): T = try {
        block()
    } catch (e: LlmError) {
        throw e
    } catch (e: java.net.SocketTimeoutException) {
        throw LlmError(
            if (e.message?.contains("connect", true) == true) "连不上接口（超时），检查手机网络"
            else "模型 3 分钟没动静，可以在模型设置里打开「关闭思考」再试"
        )
    } catch (e: java.net.UnknownHostException) {
        throw LlmError("找不到 ${runCatching { URL(url).host }.getOrDefault(url)}：检查网络，或者地址填错了")
    } catch (e: java.net.MalformedURLException) {
        throw LlmError("接口地址格式不对，要以 https:// 开头")
    } catch (e: IOException) {
        throw LlmError("连不上接口：${e.message ?: "网络错误"}")
    }

    fun chat(
        system: String,
        user: String,
        cfg: Prefs.Llm = Prefs.llm.value,
        onProgress: (String) -> Unit = {},
    ): String = chat(messages(system, listOf("user" to user)), cfg, onProgress)

    fun messages(system: String, turns: List<Pair<String, String>>): JSONArray = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", system))
        turns.forEach { (role, text) -> put(JSONObject().put("role", role).put("content", text)) }
    }

    /** 模型要做的一个动作（工具调用）：名字和参数 JSON */
    class ToolCall(val id: String, val name: String, val args: String)

    /** 带工具的回答：说的话 + 要做的动作 */
    class Reply(val text: String, val calls: List<ToolCall>)

    /** 带工具问：模型可以只说话，也可以要求做几个动作（发群、建任务），动作由 App 让你确认后执行 */
    fun chatTools(
        messages: JSONArray,
        tools: JSONArray,
        cfg: Prefs.Llm = Prefs.llm.value,
        onText: (String) -> Unit = {},
        onThink: (String) -> Unit = {},
    ): Reply = net(cfg.url) {
        val calls = ArrayList<ToolCall>()
        val text = try {
            chatRaw(messages, cfg, false, {}, onText, onThink, tools, calls)
        } catch (e: java.net.SocketTimeoutException) {
            calls.clear()
            chatRaw(messages, cfg, false, {}, onText, onThink, tools, calls)
        }
        Reply(text, calls)
    }

    /** onText：正文写到哪了（整段）；onThink：模型还在思考时，思考的内容（整段） */
    fun chat(
        messages: JSONArray,
        cfg: Prefs.Llm = Prefs.llm.value,
        onProgress: (String) -> Unit = {},
        onText: (String) -> Unit = {},
        onThink: (String) -> Unit = {},
    ): String = net(cfg.url) {
        try {
            chatRaw(messages, cfg, false, onProgress, onText, onThink)
        } catch (e: java.net.SocketTimeoutException) {
            // 手机网络偶尔卡一下，再试一次
            onProgress("重试中")
            chatRaw(messages, cfg, false, onProgress, onText, onThink)
        } catch (e: LlmError) {
            // 接口不认输出上限或关思考参数：都去掉再试
            if (e.message?.contains("（400）") == true) chatRaw(messages, cfg, true, onProgress, onText, onThink) else throw e
        }
    }

    /**
     * 流式接收：模型边想边写，连接一直有数据，长会议、带思考的模型也不会等到超时。
     * 接口不支持流式、直接回整段 JSON 的也能处理。
     * 有的网关默认输出上限很小，长纪要写一半就断，所以明着给 16000
     */
    private fun chatRaw(
        messages: JSONArray,
        cfg: Prefs.Llm,
        plain: Boolean,
        onProgress: (String) -> Unit,
        onText: (String) -> Unit,
        onThink: (String) -> Unit,
        tools: JSONArray? = null,
        calls: MutableList<ToolCall>? = null,
    ): String {
        if (!cfg.ready) throw LlmError("还没配大模型")
        val c = open(base(cfg.url) + "/chat/completions", cfg.key, "POST", 180_000)
        c.connectTimeout = 30_000
        c.setRequestProperty("Accept", "text/event-stream")
        // 要了压缩，有的网关会把流式内容攒成一大块再发
        c.setRequestProperty("Accept-Encoding", "identity")
        c.doOutput = true
        val body = JSONObject()
            .put("model", cfg.model.trim())
            .put("stream", true)
            .put("messages", messages)
        if (tools != null) body.put("tools", tools).put("tool_choice", "auto")
        if (!plain) {
            body.put("max_tokens", 16000)
            if (cfg.noThink) {
                // 各家关思考的写法不一样：DeepSeek 官方认 thinking，vLLM 部署的（比如一些自建网关）认 chat_template_kwargs
                body.put("thinking", JSONObject().put("type", "disabled"))
                body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            }
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) c.read()
        val type = c.contentType ?: ""
        var cut = false
        val text = if (!type.contains("event-stream")) {
            val ch = c.read().optJSONArray("choices")?.optJSONObject(0)
            cut = ch?.optString("finish_reason") == "length"
            ch?.optJSONObject("message")?.optJSONArray("tool_calls").objs().forEach { t ->
                val f = t.optJSONObject("function") ?: return@forEach
                calls?.add(ToolCall(t.optString("id"), f.optString("name"), f.optString("arguments")))
            }
            ch?.optJSONObject("message")?.optStr("content") ?: ""
        } else {
            val sb = StringBuilder()
            val think = StringBuilder()
            // 流式里的动作一截截来：按序号拼起来
            val parts = sortedMapOf<Int, Array<String>>()
            var lastTick = 0L
            c.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    o.optJSONObject("error")?.let { throw LlmError("模型报错：${it.optString("message")}") }
                    val ch = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    if (ch.optStr("finish_reason") == "length") cut = true
                    val d = ch.optJSONObject("delta") ?: continue
                    d.optStr("content")?.let { sb.append(it) }
                    d.optJSONArray("tool_calls").objs().forEach { t ->
                        val a = parts.getOrPut(t.optInt("index", parts.size)) { arrayOf("", "", "") }
                        t.optStr("id")?.let { a[0] = it }
                        t.optJSONObject("function")?.let { f ->
                            f.optStr("name")?.let { a[1] = it }
                            f.optStr("arguments")?.let { a[2] += it }
                        }
                    }
                    (d.optStr("reasoning_content") ?: d.optStr("reasoning"))?.let { think.append(it) }
                    val now = System.currentTimeMillis()
                    // 正文一段段来时刷得勤一点，看着是在写
                    if (now - lastTick > 120) {
                        lastTick = now
                        onProgress(if (sb.isEmpty()) "模型思考中" else "已写 ${sb.length} 字")
                        if (sb.isNotEmpty()) onText(clean(sb.toString()))
                        else if (think.isNotEmpty()) onThink(think.toString())
                    }
                }
            }
            parts.values.filter { it[1].isNotBlank() }.forEach { calls?.add(ToolCall(it[0], it[1], it[2])) }
            sb.toString()
        }
        var out = clean(text)
        if (out.isBlank() && !calls.isNullOrEmpty()) return ""
        if (out.isBlank()) throw LlmError(if (cut) "模型想太久，还没写就到长度上限了：在模型设置里打开「关闭思考」" else "模型没返回内容")
        if (cut) out += "\n\n（模型写到长度上限，后面没写完）"
        onText(out)
        return out
    }

    /** 有的模型把思考过程用 <think> 包着放在正文里 */
    private fun clean(t: String) = t.replace(Regex("(?s)<think>.*?(</think>|$)"), "").trim()

    /** 拉模型列表（OpenAI 兼容的 /models），拉不到就让你手填 */
    fun models(url: String, key: String): List<String> = net(url) {
        val o = open(base(url) + "/models", key, "GET", 30_000).read()
        o.optJSONArray("data").objs().mapNotNull { it.optStr("id") }.sorted()
    }

    fun test(cfg: Prefs.Llm): String = chat("你是测试助手。", "只回复两个字：好的", cfg).take(40)

    data class Minutes(val title: String, val folder: String, val body: String, val brief: String = "")

    fun parse(text: String): Minutes {
        val lines = text.lines()
        fun key(l: String) = l.replace("*", "").replace("#", "").trim()
        fun field(vararg ks: String): String {
            for (l in lines) {
                val t = key(l)
                for (k in ks) {
                    val m = Regex("^$k\\s*[：:]\\s*(.+)$").find(t) ?: continue
                    return m.groupValues[1].trim().trim('「', '」', '"', '“', '”', '《', '》', ' ')
                }
            }
            return ""
        }
        val isMeta = { l: String -> Regex("^(标题|会议名称|会议名|分类|类别|摘要|一句话摘要)\\s*[：:]").containsMatchIn(key(l)) }
        // 只有前面全是标题、分类、摘要这几行时，那条 --- 才是分隔线；正文里的横线不算
        val cut = lines.indices.firstOrNull { i ->
            lines[i].trim() == "---" && lines.take(i).all { it.isBlank() || isMeta(it) } && lines.take(i).any { isMeta(it) }
        } ?: -1
        val body = (if (cut >= 0) lines.drop(cut + 1) else lines.filterNot(isMeta)).joinToString("\n").trim()
        // 「产品评审 / 资源管理」这种一次给两个的，取第一个
        val folder = field("分类", "类别").split('/', '、', '，', ',', '|', '｜', '；').first().trim().removeSuffix("类会议").take(10)
        return Minutes(
            field("标题", "会议名称", "会议名").removeSuffix("纪要").trim().take(30), folder, body,
            field("摘要", "一句话摘要").take(40),
        )
    }

    private fun input(r: Rec, sents: List<Sentence>): String {
        val names = r.speakers.associate { it.idx to Store.speakerName(it) }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(r.startedAt)
        val folders = Prefs.folders.value
        return buildString {
            append("会议信息：$date，时长 ${clock(r.durationMs)}，说话人：")
            append(r.speakers.sortedByDescending { it.talkMs }.joinToString("、") { names[it.idx]!! })
            if (r.event.isNotBlank()) append("\n飞书日程：${r.event}（人名照这里写）")
            append("\n已有分类：")
            append(if (folders.isEmpty()) "还没有" else folders.joinToString("；") { if (it.note.isBlank()) it.name else "${it.name}（${it.note}）" })
            append("\n录音时标记的重要位置：")
            val user = r.marks.filterNot { it.gap }
            append(if (user.isEmpty()) "无" else user.sortedBy { it.atMs }.joinToString("、") { "[${clock(it.atMs)}]" })
            val gaps = r.marks.filter { it.gap }
            if (gaps.isNotEmpty()) {
                append("\n这些时段麦克风被通话占用，没录到：")
                append(gaps.joinToString("、") { "[${clock(it.atMs)}]-[${clock(it.endMs)}]" })
            }
            append("\n\n转写稿：\n")
            Exporter.paragraphs(sents).forEach { p ->
                append("[${clock(p.begin)}] ${names[p.spk] ?: "说话人 ${p.spk + 1}"}：${p.text}\n")
            }
        }
    }

    /** 出一场的纪要；标题、分类没被你手动改过才覆盖 */
    fun summarize(id: String, onProgress: (String) -> Unit = {}) {
        val r = Store.get(id) ?: return
        val sents = Store.sentences(id)
        if (sents.isEmpty()) {
            Store.update(id) { it.copy(sumState = SumState.Done, summary = "", sumError = null) }
            return
        }
        val cfg = Prefs.llm.value
        val tpl = Prefs.template(r.sumTemplate.ifBlank { Prefs.defaultTemplate.value })
        val names = r.speakers.associate { it.idx to Store.speakerName(it) }
        val m = parse(chat(system(tpl), input(r, sents), cfg, onProgress))
        if (m.body.isBlank()) throw LlmError("模型没按格式回答，换个模型或者重新生成试试")
        // 分类被你手动定过就不新建模型起的分类
        val folder = if (Store.get(id)?.folderManual == true) null else m.folder.takeIf { it.isNotBlank() }?.let { Prefs.ensureFolder(it) }
        Store.update(id) {
            // 生成期间你又点了重新生成：这次的结果作废，等下一次
            if (it.sumState == SumState.Queued) return@update it
            it.copy(
                summary = m.body,
                sumState = SumState.Done,
                sumError = null,
                sumModel = cfg.model,
                sumTemplate = tpl.id,
                brief = m.brief.ifBlank { it.brief },
                sumNames = names,
                title = if (!it.titleManual && m.title.isNotBlank()) m.title else it.title,
                folder = if (!it.folderManual && folder != null) folder else it.folder,
            )
        }
        Store.refreshTodos(id)
    }

    private const val BRIEF = """读一份会议纪要，用一句话说这场会定了什么或推进到哪，30 个字以内。直接说事，不用「本次会议」「讨论了」开头，不加引号。只输出这一句。"""

    private val briefing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 以前出的纪要没有一句话摘要的，补上（首页卡片显示） */
    fun backfillBriefs() {
        if (!Prefs.llm.value.ready || !briefing.compareAndSet(false, true)) return
        try {
            while (true) {
                val r = Store.recs.value.firstOrNull { it.sumState == SumState.Done && it.summary.isNotBlank() && it.brief.isBlank() } ?: break
                val b = chat(BRIEF, "会议：${r.title}\n\n纪要：\n${Store.minutes(r).take(4000)}")
                    .lines().firstOrNull { it.isNotBlank() }.orEmpty()
                    .replace(Regex("^(摘要|一句话摘要)\\s*[：:]"), "").trim().trim('「', '」', '"', '“', '”').take(40)
                if (b.isBlank()) break
                Store.update(r.id) { if (it.brief.isBlank()) it.copy(brief = b) else it }
            }
        } catch (e: Throwable) {
            // 网不好或模型出错：下次回到 App 再补
        } finally {
            briefing.set(false)
        }
    }

    /**
     * 让模型把所有会议的分类整理一遍：合并相近的、起好名字、每场放进去。
     * 你手动定过分类的会议不动。返回整理后的分类数
     */
    fun organize(onProgress: (String) -> Unit = {}): Int {
        val recs = Store.recs.value.filter { it.status == Status.Done && it.sentenceCount > 0 }
        if (recs.isEmpty()) throw LlmError("还没有能整理的会议")
        val fixed = recs.filter { it.folderManual && it.folder != null }
        val free = recs.filterNot { it.folderManual }
        val sys = """你帮一个产品经理整理会议分类（像文件夹）。根据每场会的标题和纪要，给出一套分类，并把每场会放进去。
要求：
- 分类 3 到 8 个，名字 2 到 6 个字，是能装下一类会议的大类，比如「资源管理」「产品评审」「招聘」。意思相近的合成一个。
- 「必须保留的分类」原样保留。已有分类能用就沿用原名。
- 每场会只放一个分类。

只输出下面这个格式，前后不要加别的话：
分类：
分类名｜一句话说明这类装什么会
---
会议编号｜分类名"""
        val user = buildString {
            append("必须保留的分类：")
            append(fixed.mapNotNull { it.folder }.distinct().joinToString("、").ifBlank { "无" })
            append("\n已有分类：")
            append(Prefs.folders.value.joinToString("；") { if (it.note.isBlank()) it.name else "${it.name}（${it.note}）" }.ifBlank { "无" })
            append("\n\n会议：\n")
            free.forEachIndexed { i, r ->
                val head = Chart.flatten(Store.minutes(r)).ifBlank { r.preview }.lines().filter { it.isNotBlank() }.take(6).joinToString(" ").take(240)
                append("${i + 1}｜${r.title}｜$head\n")
            }
        }
        val out = chat(sys, user, onProgress = onProgress)
        val lines = out.lines().map { it.replace("*", "").trim() }.filter { it.isNotEmpty() }
        val cut = lines.indexOfFirst { it == "---" }
        val folderLines = (if (cut >= 0) lines.take(cut) else lines).filter { "｜" in it || "|" in it }
        val assignLines = if (cut >= 0) lines.drop(cut + 1) else lines.filter { Regex("^\\d+\\s*[｜|]").containsMatchIn(it) }
        val folders = folderLines.mapNotNull { l ->
            val p = l.split('｜', '|').map { it.trim() }
            p.getOrNull(0)?.takeIf { it.isNotBlank() && it != "分类：" && !it.first().isDigit() }?.let { Prefs.Folder(it.take(10), p.getOrNull(1).orEmpty().take(40)) }
        }.distinctBy { it.name }
        if (folders.isEmpty()) throw LlmError("模型没按格式回答，再试一次")
        val keep = fixed.mapNotNull { it.folder }.distinct().filter { n -> folders.none { it.name == n } }.map { n ->
            Prefs.folders.value.firstOrNull { it.name == n } ?: Prefs.Folder(n)
        }
        Prefs.setFolders(folders + keep)
        assignLines.forEach { l ->
            val p = l.split('｜', '|').map { it.trim() }
            val i = p.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val name = p.getOrNull(1)?.takeIf { n -> folders.any { it.name == n } } ?: return@forEach
            val r = free.getOrNull(i - 1) ?: return@forEach
            Store.update(r.id) { it.copy(folder = name) }
        }
        return folders.size + keep.size
    }
}
