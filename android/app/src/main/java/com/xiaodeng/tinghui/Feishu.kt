package com.xiaodeng.tinghui

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 飞书开放平台：用你自己的应用（CodeX）的机器人身份。
 * 发消息、建文档、传录音、写多维表格、建任务都走这里；具体怎么同步一场会在 FeishuSync。
 */
object Feishu {
    private const val BASE = "https://open.feishu.cn/open-apis"

    class FsError(msg: String, val code: Int = 0) : IOException(msg)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private var token: String? = null
    private var tokenUntil = 0L
    private var tokenFor = ""

    /** 调一个接口，返回整个应答（data 在里面）；限流时等一下再试 */
    fun api(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject {
        repeat(3) { attempt ->
            val rb = when {
                body != null -> body.toString().toRequestBody(JSON)
                method == "GET" -> null
                else -> "{}".toRequestBody(JSON)
            }
            val req = Request.Builder().url(BASE + path).method(method, rb)
                .apply { if (auth) header("Authorization", "Bearer ${token()}") }
                .build()
            try {
                return execute(req)
            } catch (e: FsError) {
                if ((e.code == 99991400 || e.code == 429) && attempt < 2) Thread.sleep(1000L * (attempt + 1)) else throw e
            }
        }
        throw FsError("飞书一直在限流，等会儿再试")
    }

    fun data(method: String, path: String, body: JSONObject? = null): JSONObject =
        api(method, path, body).optJSONObject("data") ?: JSONObject()

    private fun execute(req: Request): JSONObject {
        http.newCall(req).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (r.code == 429) throw FsError("飞书限流", 429)
            val o = runCatching { JSONObject(s) }.getOrNull() ?: throw FsError("飞书返回看不懂（HTTP ${r.code}）")
            val err = o.optInt("code", 0)
            if (err != 0) throw FsError(explain(err, o.optString("msg")), err)
            return o
        }
    }

    private fun explain(code: Int, msg: String): String = when {
        msg.contains("cross tenant", true) -> "收件人和应用不在同一个组织，到飞书设置里重新验证"
        code == 10003 || code == 10014 -> "App ID 或 App Secret 不对"
        code == 99991672 || code == 99991679 || code == 99991663 -> "飞书应用缺权限：$msg"
        code == 230013 -> "机器人对你不可见：到开发者后台把你加进应用的可用范围"
        code == 230002 -> "机器人不在这个群里"
        else -> "飞书报错 $code：$msg"
    }

    @Synchronized
    private fun token(): String {
        val f = Prefs.feishu.value
        if (f.appId.isBlank() || f.secret.isBlank()) throw FsError("还没配置飞书")
        val key = f.appId + f.secret
        token?.let { if (key == tokenFor && System.currentTimeMillis() < tokenUntil) return it }
        val o = api(
            "POST", "/auth/v3/tenant_access_token/internal",
            JSONObject().put("app_id", f.appId).put("app_secret", f.secret), auth = false,
        )
        val t = o.getString("tenant_access_token")
        token = t
        tokenFor = key
        tokenUntil = System.currentTimeMillis() + (o.optLong("expire", 7200) - 300) * 1000
        return t
    }

    class Connected(val botName: String, val openId: String)

    /**
     * 用这组 App ID / Secret 换令牌、拿机器人名字，再查「这个应用是谁建的」——建应用的就是你。
     * 同一个人在不同组织里 ID 不一样，所以收件人跟着应用所在的组织走
     */
    fun verify(appId: String, secret: String): Connected {
        val o = api(
            "POST", "/auth/v3/tenant_access_token/internal",
            JSONObject().put("app_id", appId).put("app_secret", secret), auth = false,
        )
        val t = o.getString("tenant_access_token")
        fun get(path: String) = execute(Request.Builder().url(BASE + path).header("Authorization", "Bearer $t").build())
        val bot = try {
            get("/bot/v3/info").optJSONObject("bot")?.optString("app_name") ?: ""
        } catch (e: FsError) {
            throw FsError("这个应用还没开机器人能力：开发者后台 → 应用能力 → 机器人，开完发布一个版本")
        }
        var why = ""
        val creator = try {
            val app = get("/application/v6/applications/$appId?lang=zh_cn&user_id_type=open_id").optJSONObject("data")?.optJSONObject("app")
            app?.optStr("creator_id") ?: app?.optJSONObject("owner")?.optStr("owner_id")
        } catch (e: FsError) {
            why = e.message ?: ""
            null
        }
        if (creator == null) {
            throw FsError(
                "机器人能用，但认不出你是谁。给「${bot.ifBlank { "这个应用" }}」开「管理应用自身资源」权限（application:application:self_manage），发布版本后再点验证" +
                    if (why.isNotBlank()) "。飞书原话：$why" else ""
            )
        }
        return Connected(bot, creator)
    }

    /** 你自己：验证时查到的建应用的人 */
    fun me(): String = Prefs.feishu.value.openId.ifBlank { throw FsError("飞书设置要重新验证一次") }

    private fun multipart(path: String, parts: MultipartBody): JSONObject = execute(
        Request.Builder().url(BASE + path).header("Authorization", "Bearer ${token()}").post(parts).build()
    )

    // ---------- 消息 ----------

    fun send(receiveType: String, receiveId: String, msgType: String, content: JSONObject): String =
        data(
            "POST", "/im/v1/messages?receive_id_type=$receiveType",
            JSONObject().put("receive_id", receiveId).put("msg_type", msgType).put("content", content.toString()),
        ).optString("message_id")

    fun sendToMe(msgType: String, content: JSONObject) = send("open_id", me(), msgType, content)

    fun sendTest() {
        sendToMe("text", JSONObject().put("text", "听会已接上"))
    }

    class Chat(val id: String, val name: String)

    /** CodeX 在的群 */
    fun chats(): List<Chat> = data("GET", "/im/v1/chats?page_size=100").optJSONArray("items").objs()
        .map { Chat(it.getString("chat_id"), it.optString("name")) }

    class Member(val name: String, val openId: String)

    fun members(chatId: String): List<Member> {
        val out = ArrayList<Member>()
        var page = ""
        do {
            val d = data("GET", "/im/v1/chats/$chatId/members?member_id_type=open_id&page_size=100" + if (page.isNotBlank()) "&page_token=$page" else "")
            d.optJSONArray("items").objs().forEach { out += Member(it.optString("name"), it.optString("member_id")) }
            page = if (d.optBoolean("has_more")) d.optString("page_token") else ""
        } while (page.isNotBlank())
        return out
    }

    // ---------- 云空间 ----------

    fun rootFolder(): String = data("GET", "/drive/explorer/v2/root_folder/meta").getString("token")

    fun createFolder(name: String, parent: String): String =
        data("POST", "/drive/v1/files/create_folder", JSONObject().put("name", name).put("folder_token", parent)).getString("token")

    /** 机器人建的东西转给你；机器人自己留完全权限，后面还能接着写 */
    fun transfer(token: String, type: String, stayPut: Boolean) {
        api(
            "POST",
            "/drive/v1/permissions/$token/members/transfer_owner?type=$type&need_notification=false&remove_old_owner=false&old_owner_perm=full_access&stay_put=$stayPut",
            JSONObject().put("member_type", "openid").put("member_id", me()),
        )
    }

    fun move(token: String, type: String, folder: String) {
        api("POST", "/drive/v1/files/$token/move", JSONObject().put("type", type).put("folder_token", folder))
    }

    /** 给一个群开阅读权限：群里的人点纪要链接能直接看 */
    fun grantChat(token: String, type: String, chatId: String) {
        api(
            "POST", "/drive/v1/permissions/$token/members?type=$type&need_notification=false",
            JSONObject().put("member_type", "openchat").put("member_id", chatId).put("perm", "view"),
        )
    }

    /** 传文件到云空间文件夹；大于 20MB 分片传 */
    /** 传一个消息里用的文件（语音用 opus，要给时长），返回 file_key */
    fun uploadMsgFile(f: File, type: String, name: String, durationMs: Long = 0): String {
        val parts = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file_type", type)
            .addFormDataPart("file_name", name)
            .apply { if (durationMs > 0) addFormDataPart("duration", durationMs.toString()) }
            .addFormDataPart("file", name, f.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val o = multipart("/im/v1/files", parts)
        if (o.optInt("code") != 0) throw FsError("飞书报错 ${o.optInt("code")}：${o.optString("msg")}", o.optInt("code"))
        return o.getJSONObject("data").getString("file_key")
    }

    fun upload(f: File, name: String, folder: String): String {
        val size = f.length()
        val octet = "application/octet-stream".toMediaType()
        if (size <= 20L * 1024 * 1024) {
            val parts = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file_name", name)
                .addFormDataPart("parent_type", "explorer")
                .addFormDataPart("parent_node", folder)
                .addFormDataPart("size", size.toString())
                .addFormDataPart("file", name, f.asRequestBody(octet))
                .build()
            return multipart("/drive/v1/files/upload_all", parts).getJSONObject("data").getString("file_token")
        }
        val prep = data(
            "POST", "/drive/v1/files/upload_prepare",
            JSONObject().put("file_name", name).put("parent_type", "explorer").put("parent_node", folder).put("size", size),
        )
        val id = prep.getString("upload_id")
        val block = prep.getLong("block_size")
        val n = prep.getInt("block_num")
        f.inputStream().use { input ->
            for (i in 0 until n) {
                val len = minOf(block, size - i * block).toInt()
                val buf = ByteArray(len)
                var read = 0
                while (read < len) {
                    val k = input.read(buf, read, len - read)
                    if (k < 0) break
                    read += k
                }
                val parts = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("upload_id", id)
                    .addFormDataPart("seq", i.toString())
                    .addFormDataPart("size", len.toString())
                    .addFormDataPart("file", name, buf.toRequestBody(octet))
                    .build()
                multipart("/drive/v1/files/upload_part", parts)
            }
        }
        return data("POST", "/drive/v1/files/upload_finish", JSONObject().put("upload_id", id).put("block_num", n)).getString("file_token")
    }

    // ---------- 文档 ----------

    fun createDoc(title: String, folder: String): String =
        data("POST", "/docx/v1/documents", JSONObject().put("title", title).put("folder_token", folder))
            .getJSONObject("document").getString("document_id")

    fun setDocTitle(doc: String, title: String) {
        api(
            "PATCH", "/docx/v1/documents/$doc/blocks/$doc",
            JSONObject().put("update_text_elements", JSONObject().put("elements", JSONArray().put(JSONObject().put("text_run", JSONObject().put("content", title))))),
        )
    }

    /** 清空正文，重新写 */
    fun clearDoc(doc: String) {
        val n = data("GET", "/docx/v1/documents/$doc/blocks/$doc").optJSONObject("block")?.optJSONArray("children")?.length() ?: 0
        if (n > 0) api(
            "DELETE", "/docx/v1/documents/$doc/blocks/$doc/children/batch_delete?document_revision_id=-1",
            JSONObject().put("start_index", 0).put("end_index", n),
        )
    }

    /** 一段 Markdown 让飞书转成文档块，接到正文最后；块多了分几次插 */
    fun appendMarkdown(doc: String, md: String) {
        if (md.isBlank()) return
        val conv = data("POST", "/docx/v1/documents/blocks/convert", JSONObject().put("content_type", "markdown").put("content", md))
        val blocks = conv.optJSONArray("blocks") ?: return
        val firsts = conv.optJSONArray("first_level_block_ids") ?: return
        // 转出来的表格带着合并信息，插入时飞书不收
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            b.optJSONObject("table")?.optJSONObject("property")?.remove("merge_info")
            byId[b.getString("block_id")] = b
        }
        fun subtree(id: String, out: JSONArray) {
            val b = byId[id] ?: return
            out.put(b)
            b.optJSONArray("children")?.let { c -> for (i in 0 until c.length()) subtree(c.getString(i), out) }
        }
        // 每次最多插几百个块
        var batchIds = JSONArray()
        var batch = JSONArray()
        fun flush() {
            if (batchIds.length() == 0) return
            api(
                "POST", "/docx/v1/documents/$doc/blocks/$doc/descendant?document_revision_id=-1",
                JSONObject().put("children_id", batchIds).put("index", -1).put("descendants", batch),
            )
            batchIds = JSONArray()
            batch = JSONArray()
        }
        for (i in 0 until firsts.length()) {
            val id = firsts.getString(i)
            val part = JSONArray().also { subtree(id, it) }
            if (batch.length() + part.length() > 800) flush()
            batchIds.put(id)
            for (k in 0 until part.length()) batch.put(part.get(k))
        }
        flush()
    }

    /** 图片接到正文最后：先占一个图片块，传图，再把图填进去 */
    fun appendImage(doc: String, png: ByteArray) {
        val made = data(
            "POST", "/docx/v1/documents/$doc/blocks/$doc/children?document_revision_id=-1",
            JSONObject().put("index", -1).put("children", JSONArray().put(JSONObject().put("block_type", 27).put("image", JSONObject()))),
        )
        val blockId = made.getJSONArray("children").getJSONObject(0).getString("block_id")
        val parts = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file_name", "chart.png")
            .addFormDataPart("parent_type", "docx_image")
            .addFormDataPart("parent_node", blockId)
            .addFormDataPart("size", png.size.toString())
            .addFormDataPart("extra", JSONObject().put("drive_route_token", doc).toString())
            .addFormDataPart("file", "chart.png", png.toRequestBody("image/png".toMediaType()))
            .build()
        val tok = multipart("/drive/v1/medias/upload_all", parts).getJSONObject("data").getString("file_token")
        api("PATCH", "/docx/v1/documents/$doc/blocks/$blockId?document_revision_id=-1", JSONObject().put("replace_image", JSONObject().put("token", tok)))
    }

    // ---------- 多维表格 ----------

    class Base(val token: String, val url: String, val defaultTable: String)

    fun createBase(name: String, folder: String): Base {
        val app = data("POST", "/bitable/v1/apps", JSONObject().put("name", name).put("folder_token", folder)).getJSONObject("app")
        return Base(app.getString("app_token"), app.optString("url"), app.optString("default_table_id"))
    }

    fun createTable(base: String, name: String, fields: JSONArray): String =
        data(
            "POST", "/bitable/v1/apps/$base/tables",
            JSONObject().put("table", JSONObject().put("name", name).put("default_view_name", "全部").put("fields", fields)),
        ).getString("table_id")

    /** 表名 → 表 id */
    fun tables(base: String): Map<String, String> =
        data("GET", "/bitable/v1/apps/$base/tables?page_size=100").optJSONArray("items").objs().associate { it.getString("name") to it.getString("table_id") }

    fun deleteTable(base: String, table: String) {
        api("DELETE", "/bitable/v1/apps/$base/tables/$table")
    }

    fun addRecords(base: String, table: String, rows: List<JSONObject>): List<String> {
        val ids = ArrayList<String>()
        rows.chunked(400).forEach { chunk ->
            val d = data(
                "POST", "/bitable/v1/apps/$base/tables/$table/records/batch_create",
                JSONObject().put("records", JSONArray(chunk.map { JSONObject().put("fields", it) })),
            )
            d.optJSONArray("records").objs().forEach { ids += it.getString("record_id") }
        }
        return ids
    }

    fun updateRecord(base: String, table: String, id: String, fields: JSONObject) {
        api("PUT", "/bitable/v1/apps/$base/tables/$table/records/$id", JSONObject().put("fields", fields))
    }

    /** 按条件找行：conditions 是飞书筛选条件（field_name / operator / value） */
    fun searchRecords(base: String, table: String, conditions: JSONArray): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page = ""
        do {
            val d = data(
                "POST", "/bitable/v1/apps/$base/tables/$table/records/search?page_size=500" + if (page.isNotBlank()) "&page_token=$page" else "",
                JSONObject().put("filter", JSONObject().put("conjunction", "and").put("conditions", conditions)),
            )
            out += d.optJSONArray("items").objs()
            page = if (d.optBoolean("has_more")) d.optString("page_token") else ""
        } while (page.isNotBlank())
        return out
    }

    fun cond(field: String, op: String, vararg value: String): JSONObject =
        JSONObject().put("field_name", field).put("operator", op).put("value", JSONArray(value.toList()))

    fun deleteRecords(base: String, table: String, ids: List<String>) {
        ids.chunked(400).forEach { chunk ->
            runCatching {
                api("POST", "/bitable/v1/apps/$base/tables/$table/records/batch_delete", JSONObject().put("records", JSONArray(chunk)))
            }
        }
    }

    // ---------- 任务 ----------

    fun createTasklist(name: String): String =
        data(
            "POST", "/task/v2/tasklists?user_id_type=open_id",
            JSONObject().put("name", name).put("members", JSONArray().put(JSONObject().put("id", me()).put("type", "user").put("role", "editor"))),
        ).getJSONObject("tasklist").getString("guid")

    class Task(val guid: String, val url: String)

    /** 建一条任务，负责人是你自己 */
    fun createTask(summary: String, desc: String, due: Long, tasklist: String?): Task {
        val body = JSONObject()
            .put("summary", summary.take(250))
            .put("description", desc.take(3000))
            .put("members", JSONArray().put(JSONObject().put("id", me()).put("type", "user").put("role", "assignee")))
        if (due > 0) body.put("due", JSONObject().put("timestamp", due.toString()).put("is_all_day", false))
        if (tasklist != null) body.put("tasklists", JSONArray().put(JSONObject().put("tasklist_guid", tasklist)))
        val t = data("POST", "/task/v2/tasks?user_id_type=open_id", body).getJSONObject("task")
        return Task(t.getString("guid"), t.optString("url"))
    }

    /** 任务在飞书里勾完成了没有；任务被删了返回 null */
    fun taskDone(guid: String): Boolean? = runCatching {
        val t = data("GET", "/task/v2/tasks/$guid").getJSONObject("task")
        t.optString("completed_at", "0").let { it != "0" && it.isNotBlank() }
    }.getOrNull()

    fun completeTask(guid: String, done: Boolean) {
        api(
            "PATCH", "/task/v2/tasks/$guid",
            JSONObject().put("task", JSONObject().put("completed_at", if (done) System.currentTimeMillis().toString() else "0"))
                .put("update_fields", JSONArray().put("completed_at")),
        )
    }
}
