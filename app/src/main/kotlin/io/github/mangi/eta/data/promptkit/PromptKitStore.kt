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
    /**
     * 是否读取「追加文件」的正文。文件改动按修改时间戳即时生效，
     * 用于不改 APK、不重装就调整提示词。
     */
    val appendEnabled: Boolean = true,
) {
    fun active(): PromptKitVersion? =
        versions.firstOrNull { it.id == activeVersionId } ?: versions.firstOrNull()
}

class PromptKitStore(private val dir: File) {
    private val file = File(dir, "prompt-kit.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /** 追加文件缓存键（修改时间 + 长度）与内容，避免每次请求都读盘。 */
    private var appendStamp = -1L
    private var appendCache = ""

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
        if (m.keywordOnly) {
            val text = userInput.orEmpty()
            val hit = m.keywords.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
            if (!hit) return null
        }
        val body = m.active()?.body?.takeIf { it.isNotBlank() }
        val append = if (m.appendEnabled) appendText().takeIf { it.isNotBlank() } else null
        val parts = listOfNotNull(body, append)
        return if (parts.isEmpty()) null else parts.joinToString("\n\n")
    }

    /**
     * 追加文件正文：版本正文之外的补充段，改文件即时生效。
     *
     * 先看应用私有目录（`files/promptkit/append.md`，稳定、不受分区权限影响），
     * 再看 `/sdcard/Download/eta_append.md`（用户能直接用文件管理器改）。
     * 缓存键用「修改时间 + 长度」，两个文件之间切换也能立刻反映。
     */
    fun appendText(): String {
        val file = APPEND_CANDIDATES_PREFIX
            .asSequence()
            .map { path -> if (path.startsWith("/")) File(path) else File(dir, path) }
            .firstOrNull { it.isFile && it.length() > 0 }
            ?: return ""
        val stamp = file.lastModified() + file.length()
        if (stamp == appendStamp && appendCache.isNotEmpty()) return appendCache
        val text = runCatching { file.readText() }
            .getOrNull()
            ?.replace("\uFEFF", "")
            ?.trim()
            .orEmpty()
        appendStamp = stamp
        appendCache = text
        return text
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
            append(" 字 · 追加 ")
            when {
                !m.appendEnabled -> append("已关闭")
                else -> {
                    val append = appendText()
                    append(if (append.isEmpty()) "无文件" else "${append.length} 字")
                }
            }
        }
    }

    private companion object {
        /** 相对路径按 [dir] 解析，绝对路径直接使用。 */
        val APPEND_CANDIDATES_PREFIX = listOf("append.md", "/storage/emulated/0/Download/eta_append.md")
    }
}
