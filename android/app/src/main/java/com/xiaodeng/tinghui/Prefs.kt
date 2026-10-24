package com.xiaodeng.tinghui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Prefs {
    private lateinit var sp: SharedPreferences

    val DEFAULT_HOTWORDS = listOf(
        "大模型", "算力", "概览页", "利用率", "原型", "需求", "PRD", "Agent", "RAG", "MoE",
        "token", "Claude", "GPU", "H100", "昇腾", "CANN", "推理", "训练", "微调",
    ).joinToString("\n")

    private val _hotwords = MutableStateFlow("")
    val hotwords: StateFlow<String> = _hotwords
    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey
    private val _batteryTipDone = MutableStateFlow(false)
    val batteryTipDone: StateFlow<Boolean> = _batteryTipDone

    /** 飞书：用你自己的飞书应用（App ID + Secret 在设置里填）的机器人，把转写稿私聊发给你 */
    data class Feishu(
        val appId: String,
        val secret: String,
        val withAudio: Boolean,
        val botName: String = "",
        /** 你在这个应用所在组织里的 open_id：验证时查「应用是谁建的」得到 */
        val openId: String = "",
    ) {
        val ready get() = appId.isNotBlank() && secret.isNotBlank() && openId.isNotBlank()
        val userName get() = BuildConfig.FEISHU_USER_NAME.ifBlank { "你" }
    }
    private val _feishu = MutableStateFlow(Feishu("", "", false))
    val feishu: StateFlow<Feishu> = _feishu

    /** 出纪要用的大模型：OpenAI 兼容接口，地址、模型、Key 都自己填 */
    data class Llm(val url: String, val key: String, val model: String, val auto: Boolean, val noThink: Boolean = false) {
        val ready get() = url.isNotBlank() && model.isNotBlank()
    }
    private val _llm = MutableStateFlow(Llm("", "", "", true))
    val llm: StateFlow<Llm> = _llm

    /**
     * 飞书日历：走飞书自带的 CalDAV 同步（飞书里生成的用户名和密码），不用开放平台权限。
     * lead：开会前几分钟提醒
     */
    data class Cal(val user: String, val pass: String, val lead: Int = 2, val remind: Boolean = true) {
        val ready get() = user.isNotBlank() && pass.isNotBlank()
    }
    private val _cal = MutableStateFlow(Cal("", ""))
    val cal: StateFlow<Cal> = _cal

    /**
     * 飞书里的「听会」：云空间文件夹、会议库多维表格（会议、待办、结论三张表）、任务清单、各分类的子文件夹。
     * 第一次同步时建好，记在这里
     */
    data class Space(
        val folder: String = "",
        val base: String = "",
        val baseUrl: String = "",
        val tMeet: String = "",
        val tTodo: String = "",
        val tPoint: String = "",
        val tasklist: String = "",
        val folders: Map<String, String> = emptyMap(),
    ) {
        val ready get() = folder.isNotBlank() && base.isNotBlank() && tMeet.isNotBlank() && tTodo.isNotBlank() && tPoint.isNotBlank()
        val folderUrl get() = if (folder.isBlank()) "" else "https://feishu.cn/drive/folder/$folder"

        fun toJson(): JSONObject = JSONObject().put("folder", folder).put("base", base).put("baseUrl", baseUrl)
            .put("tMeet", tMeet).put("tTodo", tTodo).put("tPoint", tPoint).put("tasklist", tasklist)
            .put("folders", JSONObject(folders))

        companion object {
            fun fromJson(s: String?): Space = runCatching {
                val o = JSONObject(s!!)
                Space(
                    o.optString("folder"), o.optString("base"), o.optString("baseUrl"),
                    o.optString("tMeet"), o.optString("tTodo"), o.optString("tPoint"), o.optString("tasklist"),
                    o.optJSONObject("folders")?.let { f -> f.keys().asSequence().associateWith { f.getString(it) } } ?: emptyMap(),
                )
            }.getOrDefault(Space())
        }
    }
    private val _space = MutableStateFlow(Space())
    val space: StateFlow<Space> = _space

    /** CodeX 在飞书里给你发什么、出完纪要自动做什么 */
    data class Push(
        val before: Boolean = true,
        val after: Boolean = true,
        val autoSync: Boolean = true,
        val autoTasks: Boolean = false,
    )
    private val _push = MutableStateFlow(Push())
    val push: StateFlow<Push> = _push

    /** 你在飞书里的名字（全名），认待办是不是你的、群里 @ 人时用 */
    private val _feishuName = MutableStateFlow("")
    val feishuName: StateFlow<String> = _feishuName
    /** 上次发到的群 */
    private val _lastGroup = MutableStateFlow("")
    val lastGroup: StateFlow<String> = _lastGroup

    /** 你说话的样子：从你在会上说的话里总结的习惯和例句，往外发的字照着写 */
    data class VoiceStyle(val habits: String = "", val examples: List<String> = emptyList(), val count: Int = 0, val at: Long = 0)
    private val _voiceStyle = MutableStateFlow(VoiceStyle())
    val voiceStyle: StateFlow<VoiceStyle> = _voiceStyle

    /** 分类（文件夹）：名字 + 一句说明，说明会告诉大模型这类会装什么 */
    data class Folder(val name: String, val note: String = "")
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders

    /** 纪要模板：只管「纪要正文怎么写」；标题、分类的格式是固定的 */
    data class Template(val id: String, val name: String, val body: String)
    private val _templates = MutableStateFlow<List<Template>>(emptyList())
    val templates: StateFlow<List<Template>> = _templates
    private val _defaultTemplate = MutableStateFlow("general")
    val defaultTemplate: StateFlow<String> = _defaultTemplate

    val BUILTIN_TEMPLATES = listOf(
        Template(
            "general", "通用会议",
            """## 结论
- 定下来的事，一条一件。有前提或依据的写在后面 [时间]

## 待办
| 负责人 | 事项 | 截止 |
（会上说了谁做什么才写。没说截止就空着。事项后面带时间）

## 讨论过程
### 按话题分，小标题用话题本身，比如「概览页的筛选项」
- 谁提了什么，别人怎么回应，最后怎么定的 [时间]
- 会上给出的数字、例子、对比，照原样写

## 没定下来的
- 问题本身，卡在哪，会上说了谁跟进就写上

## 原话
- 「……」——说话人 [时间]
（挑 2 到 4 句最能代表态度或决定的原话）

某一节没内容就整节不写。""",
        ),
        Template(
            "review", "需求评审",
            """## 评审结论
- 通过、不通过还是改完再评，后面跟一句理由 [时间]

## 要改的地方
| 改什么 | 谁提的 | 会上说怎么改 |

## 讨论过程
### 按需求点或页面分
- 提出的问题、给的方案、各方的意见和最后的取舍 [时间]

## 风险和依赖
- 技术、数据、排期上的风险，依赖哪个团队或系统 [时间]

## 待办
| 负责人 | 事项 | 截止 |

## 没定下来的
- 问题，卡在哪，谁跟进

某一节没内容就整节不写。""",
        ),
        Template(
            "weekly", "周会",
            """## 本周进展
| 人 | 做了什么 | 到哪一步 |

## 风险和卡点
- 卡在哪，需要谁帮忙 [时间]

## 下周计划
| 负责人 | 事项 | 截止 |

## 需要协调的事
- 找谁，要什么 [时间]

## 其他
- 会上提到、但不属于上面几项的事

某一节没内容就整节不写。""",
        ),
        Template(
            "interview", "用户访谈",
            """## 受访者
- 身份、角色、在什么场景下用（会上提到的）

## 现在怎么做
- 他们现在的流程和用的工具，按步骤写 [时间]

## 痛点
- 一条一个，后面跟原话「……」[时间]

## 想要什么
- 他们自己提的期望，分清楚是明确要的还是顺口一提

## 我们这边的判断
- 我方人员在会上说出来的判断和下一步（没说就不写）

## 待验证
- 会上说要回头确认的事

某一节没内容就整节不写。""",
        ),
    )

    /** 老版本的内置模板正文：用户没改过的，升级时换成新的 */
    private val OLD_BUILTIN = mapOf(
        "general" to """## 结论
- 会上定下来的事
## 待办
- 谁：做什么，截止时间（没说就不写）[时间]
## 讨论要点
### 一个话题一个小标题
- 要点 [时间]
## 还没定的
- 没结论的问题
某一节没内容就整节不写。""",
        "review" to """## 评审结论
- 通过、不通过还是改完再评，一句话说原因
## 要改的地方
- 改什么，谁提的 [时间]
## 待办
- 谁：做什么，截止时间（没说就不写）[时间]
## 有争议的
- 各方怎么说 [时间]
## 风险
- 会上提到的风险 [时间]
某一节没内容就整节不写。""",
        "weekly" to """## 各人进展
### 人名
- 做了什么，到哪一步 [时间]
## 风险和卡点
- 
## 接下来要做的
- 谁：做什么，截止时间（没说就不写）[时间]
## 要别人配合的
- 
某一节没内容就整节不写。""",
        "interview" to """## 受访者
- 身份、背景（会上提到的）
## 痛点
- 一句话概括，后面引原话「……」[时间]
## 想要什么
- 
## 值得注意的
- 
## 待验证
- 
某一节没内容就整节不写。""",
    )

    fun template(id: String?): Template =
        _templates.value.firstOrNull { it.id == id }
            ?: _templates.value.firstOrNull { it.id == _defaultTemplate.value }
            ?: _templates.value.firstOrNull() ?: BUILTIN_TEMPLATES[0]

    fun saveTemplate(t: Template) {
        val list = _templates.value
        setTemplates(if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t)
    }

    fun deleteTemplate(id: String) {
        setTemplates(_templates.value.filter { it.id != id })
        if (_defaultTemplate.value == id) setDefaultTemplate(_templates.value.firstOrNull()?.id ?: "general")
    }

    fun setDefaultTemplate(id: String) {
        _defaultTemplate.value = id
        sp.edit().putString("tplDefault", id).apply()
    }

    private fun setTemplates(list: List<Template>) {
        _templates.value = list
        sp.edit().putString(
            "templates",
            org.json.JSONArray(list.map { org.json.JSONObject().put("id", it.id).put("name", it.name).put("body", it.body) }).toString(),
        ).apply()
    }

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("tinghui", Context.MODE_PRIVATE)
        _hotwords.value = sp.getString("hotwords", null) ?: DEFAULT_HOTWORDS
        // 打包带的热词（常一起开会的人、产品名）：这一批没合并过就补进去，已有的不重复；你删掉的下一批之前不会再加回来
        val packed = BuildConfig.HOTWORDS.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (packed.isNotEmpty() && sp.getInt("hotwordsPacked", 0) != BuildConfig.HOTWORDS.hashCode()) {
            val have = _hotwords.value.lines().map { it.trim() }.toSet()
            val add = packed.filter { it !in have }
            if (add.isNotEmpty()) _hotwords.value = (_hotwords.value.trimEnd() + "\n" + add.joinToString("\n")).trim()
            sp.edit().putString("hotwords", _hotwords.value).putInt("hotwordsPacked", BuildConfig.HOTWORDS.hashCode()).apply()
        }
        _apiKey.value = sp.getString("apiKey", null)?.ifBlank { null } ?: BuildConfig.DASHSCOPE_KEY
        _batteryTipDone.value = sp.getBoolean("batteryTipDone", false)
        // 没手动配过就用打包时带的默认配置
        val set = sp.contains("llmUrl")
        _llm.value = Llm(
            url = if (set) sp.getString("llmUrl", "") ?: "" else BuildConfig.LLM_URL,
            key = if (set) sp.getString("llmKey", "") ?: "" else BuildConfig.LLM_KEY,
            model = if (set) sp.getString("llmModel", "") ?: "" else BuildConfig.LLM_MODEL,
            auto = sp.getBoolean("llmAuto", true),
            noThink = if (set) sp.getBoolean("llmNoThink", false) else BuildConfig.LLM_URL.isNotBlank(),
        )
        _templates.value = sp.getString("templates", null)?.let { raw ->
            runCatching { org.json.JSONArray(raw).objs().map { Template(it.getString("id"), it.getString("name"), it.getString("body")) } }.getOrNull()
        }?.map { t ->
            // 内置模板没被改过的，换成新版本
            if (OLD_BUILTIN[t.id]?.trim() == t.body.trim()) BUILTIN_TEMPLATES.first { it.id == t.id } else t
        } ?: BUILTIN_TEMPLATES
        _defaultTemplate.value = sp.getString("tplDefault", "general") ?: "general"
        _folders.value = runCatching {
            org.json.JSONArray(sp.getString("folders", "[]")).objs().map { Folder(it.getString("name"), it.optString("note")) }
        }.getOrDefault(emptyList())
        // 自己装的包带着日历账号和文档链接；在设置里改过就用改过的
        _cal.value = Cal(
            sp.getString("calUser", null) ?: BuildConfig.CALDAV_USER, sp.getString("calPass", null) ?: BuildConfig.CALDAV_PASS,
            sp.getInt("calLead", 2), sp.getBoolean("calRemind", true),
        )
        _space.value = Space.fromJson(sp.getString("fsSpace", null) ?: BuildConfig.FEISHU_SPACE.ifBlank { null })
        _push.value = Push(
            sp.getBoolean("pushBefore", true), sp.getBoolean("pushAfter", true),
            sp.getBoolean("autoSync", true), sp.getBoolean("autoTasks", false),
        )
        _feishuName.value = sp.getString("fsName", "") ?: ""
        _lastGroup.value = sp.getString("lastGroup", "") ?: ""
        _voiceStyle.value = runCatching {
            val o = JSONObject(sp.getString("voiceStyle", null)!!)
            VoiceStyle(o.optString("habits"), o.optJSONArray("examples").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) } }, o.optInt("count"), o.optLong("at"))
        }.getOrDefault(VoiceStyle())
        // 没在设置里接过飞书，就用打包时带的（自己装的包有，分享版没有）
        val fsSet = !sp.getString("fsAppId", "").isNullOrBlank()
        _feishu.value = Feishu(
            appId = if (fsSet) sp.getString("fsAppId", "") ?: "" else BuildConfig.FEISHU_APP_ID,
            secret = if (fsSet) sp.getString("fsSecret", "") ?: "" else BuildConfig.FEISHU_APP_SECRET,
            withAudio = sp.getBoolean("fsAudio", false),
            botName = if (fsSet) sp.getString("fsBot", "") ?: "" else if (BuildConfig.FEISHU_APP_ID.isNotBlank()) "CodeX" else "",
            openId = if (fsSet) sp.getString("fsOpenId", "") ?: "" else BuildConfig.FEISHU_OPEN_ID,
        )
    }

    fun setCal(c: Cal) {
        _cal.value = c
        sp.edit().putString("calUser", c.user).putString("calPass", c.pass).putInt("calLead", c.lead).putBoolean("calRemind", c.remind).apply()
    }

    fun setSpace(s: Space) {
        _space.value = s
        sp.edit().putString("fsSpace", s.toJson().toString()).apply()
    }

    fun setPush(p: Push) {
        _push.value = p
        sp.edit().putBoolean("pushBefore", p.before).putBoolean("pushAfter", p.after)
            .putBoolean("autoSync", p.autoSync).putBoolean("autoTasks", p.autoTasks).apply()
    }

    fun setFeishuName(n: String) {
        if (n.isBlank() || n == _feishuName.value) return
        _feishuName.value = n
        sp.edit().putString("fsName", n).apply()
    }

    fun setLastGroup(id: String) {
        _lastGroup.value = id
        sp.edit().putString("lastGroup", id).apply()
    }

    fun setVoiceStyle(v: VoiceStyle) {
        _voiceStyle.value = v
        sp.edit().putString(
            "voiceStyle",
            JSONObject().put("habits", v.habits).put("examples", org.json.JSONArray(v.examples)).put("count", v.count).put("at", v.at).toString(),
        ).apply()
    }

    fun setFeishu(f: Feishu) {
        _feishu.value = f
        sp.edit()
            .putString("fsAppId", f.appId.trim())
            .putString("fsSecret", f.secret.trim())
            .putBoolean("fsAudio", f.withAudio)
            .putString("fsBot", f.botName)
            .putString("fsOpenId", f.openId)
            .apply()
    }

    fun setLlm(l: Llm) {
        _llm.value = l
        sp.edit()
            .putString("llmUrl", l.url.trim())
            .putString("llmKey", l.key.trim())
            .putString("llmModel", l.model.trim())
            .putBoolean("llmAuto", l.auto)
            .putBoolean("llmNoThink", l.noThink)
            .apply()
    }

    fun setFolders(list: List<Folder>) {
        _folders.value = list
        sp.edit().putString(
            "folders",
            org.json.JSONArray(list.map { org.json.JSONObject().put("name", it.name).put("note", it.note) }).toString(),
        ).apply()
    }

    /** 大模型新起的分类自动加进来；已有的（不分大小写）就用已有的名字 */
    @Synchronized
    fun ensureFolder(name: String): String {
        val n = name.trim()
        _folders.value.firstOrNull { it.name.equals(n, true) }?.let { return it.name }
        setFolders(_folders.value + Folder(n))
        return n
    }

    fun setHotwords(v: String) {
        _hotwords.value = v
        sp.edit().putString("hotwords", v).apply()
    }

    fun setApiKey(v: String) {
        _apiKey.value = v.trim().ifBlank { BuildConfig.DASHSCOPE_KEY }
        sp.edit().putString("apiKey", v.trim()).apply()
    }

    fun setBatteryTipDone() {
        _batteryTipDone.value = true
        sp.edit().putBoolean("batteryTipDone", true).apply()
    }

    /**
     * 热词规则（百炼文档）：含中文的不超过 15 个字；纯英文按空格切不超过 7 段。不合规的跳过。
     * 声纹库里的人名也一起当热词。
     */
    /** extra：这场会日程里的参会人名字，转写时一起当热词 */
    fun hotwordList(extra: List<String> = emptyList()): List<String> {
        val words = extra + _hotwords.value.lines() + Voice.people.value.map { it.name }
        return words.map { it.trim() }.filter { it.isNotEmpty() && validHotword(it) }.distinct().take(2000)
    }

    fun validHotword(w: String): Boolean =
        if (w.any { it.code > 0x7F }) w.length <= 15 else w.split(' ').filter { it.isNotEmpty() }.size <= 7
}
