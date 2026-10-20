package io.github.mangi.eta.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.data.promptkit.PromptKitBridge
import io.github.mangi.eta.data.promptkit.PromptKitManifest
import io.github.mangi.eta.data.promptkit.PromptKitSkillSync
import io.github.mangi.eta.data.promptkit.SkillBundlePolicy
import java.io.File
import io.github.mangi.eta.data.promptkit.PromptKitVersion
import io.github.mangi.eta.ui.model.PromptKitUiState
import io.github.mangi.eta.ui.model.PromptKitVersionItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 提示词包页的读取与写入状态。
 *
 * 数据源就是 PromptKitBridge 用的那份本地文件，与 prompt_kit 工具共享；
 * 这里只做界面状态，不缓存第二份正文，也不参与请求组装。
 */
internal class AgentPromptKitStore(private val scope: CoroutineScope) {
    var state by mutableStateOf(PromptKitUiState())
        private set

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            // 进入页面留一个时间戳标记：用来区分「根本没跳转」和「跳转了但读不到正文」。
            runCatching {
                PromptKitBridge.contextOrNull()?.let { ctx ->
                    val dir = java.io.File(ctx.filesDir, "promptkit").apply { mkdirs() }
                    java.io.File(dir, ".last-open.json").writeText(
                        "{\"at\":${System.currentTimeMillis()}}",
                    )
                }
            }
            val manifest = PromptKitBridge.manifest()
            withContext(Dispatchers.Main) { state = fromManifest(manifest) }
        }
    }

    fun toggleEnabled(enabled: Boolean) {
        // 先把「正在同步技能…」写进界面状态：同步要经 root 拷一批技能目录（首次或技能集合变化时），
        // 之前是同步返回后才更新 state，界面在这几秒里毫无反馈，看着就像卡住。
        if (enabled) state = state.copy(notice = "正在同步技能…")
        scope.launch(Dispatchers.IO) {
            val manifest = PromptKitBridge.update { it.copy(enabled = enabled) }
            val context = PromptKitBridge.contextOrNull()
            val syncNotice = if (enabled && context != null) {
                PromptKitSkillSync.sync(context).message
            } else {
                null
            }
            withContext(Dispatchers.Main) {
                state = fromManifest(manifest).copy(notice = syncNotice)
            }
        }
    }

    /** 从用户选中的 txt/md 读入正文；读到的内容只进草稿，仍需点「保存正文」才落盘。 */    fun importBodyFile(uri: String) {
        scope.launch(Dispatchers.IO) {
            val limit = 512 * 1024
            // 这个 store 没有自己的 context（不像 AgentSkillsStore），上下文统一从 Bridge 取。
            val context = PromptKitBridge.contextOrNull()
            val bytes = context?.let { ctx ->
                runCatching {
                    ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))
                        ?.use { stream -> stream.readBytes() }
                }.getOrNull()
            }
            val text = bytes?.takeIf { it.size <= limit }?.toString(Charsets.UTF_8)
            val versionId = state.activeVersionId
            val store = PromptKitBridge.storeOrNull()
            when {
                bytes == null -> withContext(Dispatchers.Main) {
                    state = state.copy(notice = "导入失败：读不到文件")
                }
                bytes.size > limit -> withContext(Dispatchers.Main) {
                    state = state.copy(notice = "文件超过 512KB 上限")
                }
                text.isNullOrBlank() -> withContext(Dispatchers.Main) {
                    state = state.copy(notice = "导入失败：文件为空")
                }
                store == null || versionId == null -> withContext(Dispatchers.Main) {
                    state = state.copy(notice = "没有可写入的版本，请先新建或切换版本")
                }
                else -> {
                    // 直接落盘，不经草稿：输入组件对长度有上限（实测 4500），
                    // 走 draft 转一手会把长正文截断后再保存。
                    val saved = runCatching { store.upsertVersion(id = versionId, name = versionId, body = text) }
                    val manifest = PromptKitBridge.manifest()
                    withContext(Dispatchers.Main) {
                        state = if (saved.isSuccess) {
                            fromManifest(manifest).copy(notice = "已直接写入 $versionId（${text.length} 字）")
                        } else {
                            state.copy(notice = "写入失败：${saved.exceptionOrNull()?.javaClass?.simpleName}")
                        }
                    }
                }
            }
        }
    }

    fun toggleKeywordOnly(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            val manifest = PromptKitBridge.update { it.copy(keywordOnly = enabled) }
            withContext(Dispatchers.Main) { state = fromManifest(manifest) }
        }
    }

    /**
     * 「仅使用技能包内技能」开关。
     *
     * 只写技能根目录下的标记文件（[SkillBundlePolicy.FLAG_FILE]），
     * 技能索引与同步都从那里读，所以不需要额外的持久化字段，也不依赖 Context 常驻。
     */
    fun toggleBundleOnly(enabled: Boolean) {
        val context = PromptKitBridge.contextOrNull()
        SkillBundlePolicy.setBundleOnly(File(context?.filesDir ?: return@toggleBundleOnly, "skills"), enabled)
        state = state.copy(bundleOnly = enabled, notice = if (enabled) {
            "已开启：只有技能包内技能参与索引与同步"
        } else {
            "已关闭：所有已启用技能都会参与索引与同步"
        })
    }

    private fun bundleOnlyFromDisk(): Boolean {
        val context = PromptKitBridge.contextOrNull() ?: return true
        return SkillBundlePolicy.isBundleOnly(File(context.filesDir, "skills"))
    }

    fun selectVersion(id: String) {
        scope.launch(Dispatchers.IO) {
            val manifest = PromptKitBridge.update {
                if (it.versions.none { version -> version.id == id }) {
                    it.copy(
                        enabled = true,
                        activeVersionId = id,
                        versions = it.versions + PromptKitVersion(id = id, name = id),
                    )
                } else {
                    it.copy(enabled = true, activeVersionId = id)
                }
            }
            withContext(Dispatchers.Main) { state = fromManifest(manifest) }
        }
    }

    fun createVersion() {
        scope.launch(Dispatchers.IO) {
            val current = PromptKitBridge.manifest()
            val id = nextVersionId(current)
            val manifest = PromptKitBridge.update {
                it.copy(
                    enabled = true,
                    activeVersionId = id,
                    versions = it.versions + PromptKitVersion(id = id, name = id),
                )
            }
            withContext(Dispatchers.Main) {
                state = fromManifest(manifest).copy(notice = "已新建版本 $id")
            }
        }
    }

    fun removeActiveVersion() {
        val id = state.activeVersionId ?: return
        scope.launch(Dispatchers.IO) {
            PromptKitBridge.storeOrNull()?.removeVersion(id)
            val manifest = PromptKitBridge.manifest()
            withContext(Dispatchers.Main) {
                state = fromManifest(manifest).copy(notice = "已删除版本 $id")
            }
        }
    }

    fun updateDraft(content: String) {
        state = state.copy(draft = content, notice = null)
    }

    fun updateKeywords(text: String) {
        state = state.copy(keywordsDraft = text, notice = null)
    }

    fun saveBody() {
        if (!state.canSaveBody) return
        val body = state.draft
        val id = state.activeVersionId ?: nextVersionId(PromptKitBridge.manifest())
        state = state.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            PromptKitBridge.storeOrNull()?.upsertVersion(id = id, body = body)
            val manifest = PromptKitBridge.update { it.copy(activeVersionId = id) }
            withContext(Dispatchers.Main) {
                state = fromManifest(manifest).copy(notice = "已保存到版本 $id")
            }
        }
    }

    fun saveKeywords() {
        if (!state.canSaveKeywords) return
        val keywords = state.keywordsDraftList
        state = state.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            val manifest = PromptKitBridge.update { it.copy(keywords = keywords) }
            withContext(Dispatchers.Main) {
                state = fromManifest(manifest).copy(
                    notice = if (keywords.isEmpty()) {
                        "关键词已清空；关键词模式下不会注入任何提示词"
                    } else {
                        "已保存 ${keywords.size} 个关键词"
                    },
                )
            }
        }
    }

    fun dismissNotice() {
        state = state.copy(notice = null)
    }

    private fun fromManifest(manifest: PromptKitManifest): PromptKitUiState {
        val active = manifest.active()
        return state.copy(
            enabled = manifest.enabled,
            keywordOnly = manifest.keywordOnly,
            bundleOnly = bundleOnlyFromDisk(),
            keywords = manifest.keywords,
            versions = manifest.versions.map {
                PromptKitVersionItem(id = it.id, name = it.name, chars = it.body.length)
            },
            activeVersionId = manifest.activeVersionId ?: active?.id,
            draft = active?.body.orEmpty(),
            savedBody = active?.body.orEmpty(),
            keywordsDraft = manifest.keywords.joinToString("、"),
            isLoading = false,
            isSaving = false,
        )
    }

    private fun nextVersionId(manifest: PromptKitManifest): String {
        var index = manifest.versions.size + 1
        while (manifest.versions.any { it.id == "v$index" }) index++
        return "v$index"
    }
}
