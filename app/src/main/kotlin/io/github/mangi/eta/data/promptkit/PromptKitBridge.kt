package io.github.mangi.eta.data.promptkit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 提示词包的全局入口 + 原生工具实现。
 *
 * 接入方式（三处，一次接完即可）：
 *  1. `EtaApp.onCreate()` 调 `PromptKitBridge.init(this)`；
 *  2. 系统提示词组装处：`PromptKitBridge.promptFor(userInput)?.let { messages.put(systemMessage(it)) }`；
 *  3. 工具目录里登记 `PromptKitTool.NAME` / `PromptKitTool.descriptor()`，
 *     分派处调 `PromptKitTool.execute(args)`。
 *
 * 本文件不内置任何提示词正文：正文由用户写入，工具只负责存取与开关。
 */
object PromptKitBridge {
    @Volatile
    private var store: PromptKitStore? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (store == null) store = PromptKitStore(File(context.filesDir, "promptkit"))
    }

    /** 应用级 Context；技能同步这类需要环境路径的操作才用得到。 */
    fun contextOrNull(): Context? = appContext

    fun isReady(): Boolean = store != null

    fun promptFor(userInput: String?): String? = store?.promptFor(userInput)

    fun statusLine(): String = store?.statusLine() ?: "提示词包未初始化"

    fun manifest(): PromptKitManifest = store?.load() ?: PromptKitManifest()

    fun update(block: (PromptKitManifest) -> PromptKitManifest): PromptKitManifest {
        val s = store ?: return PromptKitManifest()
        val next = block(s.load())
        s.save(next)
        return next
    }

    fun storeOrNull(): PromptKitStore? = store
}

object PromptKitTool {
    const val NAME = "prompt_kit"

    /** OpenAI 风格的函数描述；由调用方按现有目录格式包一层。 */
    fun descriptor(): JSONObject = JSONObject()
        .put("name", NAME)
        .put(
            "description",
            "查询和切换本机提示词包（PromptKit）。启用的版本正文会叠加到系统提示词上。" +
                "action=status 查状态、list_versions 列版本、enable/disable 总开关、set_version 切换版本、" +
                "set_body 写入正文、set_keywords 设触发关键词、remove_version 删除版本、" +
                "sync_skills 把已启用技能同步到 Linux 环境的 /root/skills。" +
                "本工具不内置任何提示词正文：正文只能来自用户，不要自行编造或填充正文。",
        )
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put(
                    "properties",
                    JSONObject()
                    .put(
                        "path",
                        JSONObject().put("type", "string").put(
                            "description",
                            "txt/md 文件的绝对路径（set_body_file 用；UTF-8，512KB 上限）",
                        ),
                    )
                        .put(
                            "action",
                            JSONObject().put("type", "string")
                                .put("enum", JSONArray().put("status").put("list_versions")
                                    .put("enable").put("disable").put("set_version")
                                    .put("set_body").put("set_body_file").put("set_keywords").put("remove_version")
                                    .put("sync_skills"))
                                .put(
                                    "description",
                                    "要执行的动作：status 只读状态、list_versions 列版本、enable/disable 总开关、" +
                                        "set_version 切到某个版本、set_body 写入或覆盖正文、set_keywords 设关键词、" +
                                        "remove_version 删除版本。",
                                ),
                        )
                        .put("version", JSONObject().put("type", "string").put("description", "版本 id，例如 v1"))
                        .put("name", JSONObject().put("type", "string").put("description", "版本显示名，默认与 id 相同"))
                        .put("body", JSONObject().put("type", "string").put("description", "提示词正文，仅 set_body 使用；必须来自用户"))
                        .put("keywords", JSONObject().put("type", "array").put("description", "触发关键词，命中任一即注入")
                            .put("items", JSONObject().put("type", "string")))
                        .put("keyword_only", JSONObject().put("type", "boolean").put("description", "true 只在命中关键词时注入，false 每轮都注入；省略则沿用当前设置")),
                )
                .put("required", JSONArray().put("action")),
        )

    /** 返回给模型的文本结果。 */
    fun execute(args: JSONObject): String {
        val store = PromptKitBridge.storeOrNull()
            ?: return error("PROMPT_KIT_UNAVAILABLE", "提示词包未初始化")
        val action = args.optString("action").trim().lowercase()
        if (action.isEmpty()) return error("INVALID_ARGUMENT", "缺少 action")

        return when (action) {
            "status" -> JSONObject()
                .put("ok", true)
                .put("status", store.statusLine())
                .put("enabled", store.load().enabled)
                .put("active_version", store.load().active()?.id ?: JSONObject.NULL)
                .toString()

            "list_versions" -> {
                val m = store.load()
                JSONObject()
                    .put("ok", true)
                    .put("active_version", m.activeVersionId ?: JSONObject.NULL)
                    .put(
                        "versions",
                        JSONArray().apply {
                            m.versions.forEach { v ->
                                put(
                                    JSONObject()
                                        .put("id", v.id)
                                        .put("name", v.name)
                                        .put("chars", v.body.length),
                                )
                            }
                        },
                    )
                    .toString()
            }

            "enable" -> {
                val version = args.optString("version").ifBlank { null }
                PromptKitBridge.update { m ->
                    if (version != null && m.versions.none { it.id == version }) {
                        m.copy(
                            enabled = true,
                            activeVersionId = version,
                            versions = m.versions + PromptKitVersion(id = version, name = version),
                        )
                    } else {
                        m.copy(enabled = true, activeVersionId = version ?: m.activeVersionId)
                    }
                }
                ok("已启用；" + store.statusLine() + syncSkillsSuffix())
            }

            "sync_skills" -> {
                val context = PromptKitBridge.contextOrNull()
                    ?: return error("PROMPT_KIT_UNAVAILABLE", "提示词包未初始化")
                ok(PromptKitSkillSync.sync(context, force = true).message)
            }

            "disable" -> {
                PromptKitBridge.update { it.copy(enabled = false) }
                ok("已关闭，模型恢复默认系统提示词")
            }

            "set_version" -> {
                val version = args.optString("version").trim()
                if (version.isEmpty()) return error("INVALID_ARGUMENT", "缺少 version")
                PromptKitBridge.update { it.copy(activeVersionId = version, enabled = true) }
                ok("已切到 $version")
            }

            "set_body" -> {
                val version = args.optString("version").trim()
                if (version.isEmpty()) return error("INVALID_ARGUMENT", "缺少 version")
                val body = args.optString("body")
                val name = args.optString("name").ifBlank { version }
                store.upsertVersion(id = version, name = name, body = body)
                ok("已写入 $version（${body.length} 字）")
            }

            "set_body_file" -> {
                val version = args.optString("version").trim()
                if (version.isEmpty()) return error("INVALID_ARGUMENT", "缺少 version")
                val path = args.optString("path").trim()
                if (path.isEmpty()) return error("INVALID_ARGUMENT", "缺少 path")
                val file = java.io.File(path)
                if (!file.isFile) return error("FILE_NOT_FOUND", "找不到文件：$path")
                // 上限：提示词正文每轮都会随请求发出去，超大文本会直接推高 token 与首 token 延迟。
                val limit = 512 * 1024
                if (file.length() > limit) {
                    return error("FILE_TOO_LARGE", "文件 ${file.length()} 字节，超过 512KB 上限")
                }
                val body = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
                    return error("READ_FAILED", "读取失败：${it.javaClass.simpleName}")
                }
                if (body.isBlank()) return error("EMPTY_BODY", "文件内容为空")
                val name = args.optString("name").ifBlank { version }
                store.upsertVersion(id = version, name = name, body = body)
                ok("已从 ${file.name} 读入 $version（${body.length} 字）")
            }

            "set_keywords" -> {
                val arr = args.optJSONArray("keywords")
                val list = buildList {
                    if (arr != null) for (i in 0 until arr.length()) {
                        val s = arr.optString(i).trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
                val only = args.optBoolean("keyword_only", store.load().keywordOnly)
                PromptKitBridge.update { it.copy(keywords = list, keywordOnly = only) }
                ok(
                    if (only) "已设为关键词模式：${list.joinToString("、").ifEmpty { "（无关键词，等于不注入）" }}"
                    else "已设为全程注入",
                )
            }

            "remove_version" -> {
                val version = args.optString("version").trim()
                if (version.isEmpty()) return error("INVALID_ARGUMENT", "缺少 version")
                store.removeVersion(version)
                ok("已删除版本 $version")
            }

            else -> error("INVALID_ARGUMENT", "不支持的 action：$action")
        }
    }

    /** 打开总开关时顺带把已启用技能镜像到 Linux 环境；同步失败不影响启用本身。 */
    private fun syncSkillsSuffix(): String {
        val context = PromptKitBridge.contextOrNull() ?: return ""
        return "；" + PromptKitSkillSync.sync(context).message
    }

    private fun ok(message: String) = JSONObject().put("ok", true).put("message", message).toString()

    private fun error(code: String, message: String) =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()
}
