package io.github.mangi.eta.data.promptkit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 提示词包（PromptKit）：多版本、可编辑的附加提示词。
 *
 * 设计参考第三方客户端 MilkCode 的同类机制，但内容与版本完全由本地决定：
 *  - 版本以纯文本存放，可随时编辑、新增、切换、还原；
 *  - 启用后叠加到当前模型配置的系统提示词上，对内置服务与自建服务商同时生效；
 *  - 可选「关键词模式」：只有本轮输入命中关键词时才注入。
 *
 * 本文件不内置任何提示词正文；正文由用户在设置页或通过 prompt_kit 工具写入。
 */
@Serializable
data class PromptKitVersion(
    val id: String,
    val name: String,
    val body: String = "",
)

@Serializable
data class PromptKitManifest(
    val enabled: Boolean = false,
    val activeVersionId: String? = null,
    val versions: List<PromptKitVersion> = emptyList(),
    /** 关键词模式：只有命中关键词时才注入。 */
    val keywordOnly: Boolean = false,
    val keywords: List<String> = emptyList(),
    /** 叠加方式：true 时替换原有系统提示词末尾的额外段落，false 时追加。 */
    val replaceExtras: Boolean = false,
) {
    fun active(): PromptKitVersion? =
        versions.firstOrNull { it.id == activeVersionId } ?: versions.firstOrNull()
}

class PromptKitStore(private val dir: File) {
    private val file = File(dir, "prompt-kit.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): PromptKitManifest =
        runCatching { json.decodeFromString<PromptKitManifest>(file.readText()) }.getOrElse { PromptKitManifest() }

    fun save(manifest: PromptKitManifest) {
        runCatching {
            dir.mkdirs()
            file.writeText(json.encodeToString(manifest))
        }
    }

    fun upsertVersion(id: String, name: String? = null, body: String? = null) {
        val m = load()
        val existing = m.versions.firstOrNull { it.id == id }
        val updated = if (existing == null) {
            m.versions + PromptKitVersion(id = id, name = name ?: id, body = body.orEmpty())
        } else {
            m.versions.map {
                if (it.id == id) it.copy(name = name ?: it.name, body = body ?: it.body) else it
            }
        }
        save(m.copy(versions = updated))
    }

    fun removeVersion(id: String) {
        val m = load()
        save(
            m.copy(
                versions = m.versions.filterNot { it.id == id },
                activeVersionId = if (m.activeVersionId == id) null else m.activeVersionId,
            ),
        )
    }

    /** 本轮应该注入的提示词；不需要注入时返回 null。 */
    fun promptFor(userInput: String?): String? {
        val m = load()
        if (!m.enabled) return null
        val version = m.active() ?: return null
        if (version.body.isBlank()) return null
        if (m.keywordOnly) {
            val text = userInput.orEmpty()
            val hit = m.keywords.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
            if (!hit) return null
        }
        return version.body
    }

    /** 供工具与设置页展示的一行状态。 */
    fun statusLine(): String {
        val m = load()
        val version = m.active()
        val mode = if (m.keywordOnly) "关键词模式" else "全程"
        return buildString {
            append(if (m.enabled) "已启用" else "未启用")
            append(" · 版本 ")
            append(version?.name ?: "（无）")
            append(" · ")
            append(mode)
            if (m.keywords.isNotEmpty()) append("（${m.keywords.joinToString("、")}）")
            append(" · 正文 ")
            append(version?.body?.length ?: 0)
            append(" 字")
        }
    }
}
