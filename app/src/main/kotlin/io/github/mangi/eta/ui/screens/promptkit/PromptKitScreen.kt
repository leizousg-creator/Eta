package io.github.mangi.eta.ui.screens.promptkit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.promptkit.PromptKitBridge
import io.github.mangi.eta.data.promptkit.PromptKitVersion
import io.github.mangi.eta.ui.components.ScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 提示词包设置页（fork）。
 *
 * 能做四件事：开关、关键词模式、直接编辑正文、从 txt/md 文件导入正文。
 * 只作用于当前选中的版本；版本管理（新建/删除/改名）在旧 fork 里有，
 * 但日常用不到，先不搬，避免把页面做复杂。
 *
 * 另外 `files/promptkit/append.md` 与 `/sdcard/Download/eta_append.md` 仍支持热加载，
 * 想用文件管理器改也行，两条路都通。
 */
@Composable
private fun PkCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        content()
    }
}

@Composable
internal fun PromptKitScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val manifest = remember { mutableStateOf(PromptKitBridge.manifest()) }
    val status = remember { mutableStateOf(PromptKitBridge.statusLine()) }
    val active: PromptKitVersion? = manifest.value.active()
    val savedBody = active?.body.orEmpty()
    val bodyState = remember { TextFieldState(savedBody) }
    val notice = remember { mutableStateOf<String?>(null) }

    fun refresh(next: io.github.mangi.eta.data.promptkit.PromptKitManifest) {
        manifest.value = next
        status.value = PromptKitBridge.statusLine()
    }

    fun toggleMode(enabled: Boolean) {
        refresh(PromptKitBridge.update { it.copy(enabled = enabled) })
    }

    fun toggleKeywordOnly(value: Boolean) {
        refresh(PromptKitBridge.update { it.copy(keywordOnly = value) })
    }

    fun saveBody() {
        val id = active?.id
        if (id == null) {
            notice.value = "没有可写入的版本"
            return
        }
        val text = bodyState.text.toString()
        val store = PromptKitBridge.storeOrNull()
        if (store == null) {
            notice.value = "提示词包存储未初始化"
            return
        }
        runCatching { store.upsertVersion(id, body = text) }
            .onSuccess {
                refresh(PromptKitBridge.manifest())
                notice.value = "已保存 ${text.length} 字"
            }
            .onFailure { failure ->
                notice.value = "保存失败：${failure.javaClass.simpleName}"
            }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    }
                }.getOrNull()
            }
            if (text == null) {
                notice.value = "读取文件失败"
            } else {
                bodyState.setTextAndPlaceCursorAtEnd(text)
                notice.value = "已导入 ${text.length} 字，点「保存正文」写入"
            }
        }
    }

    ScaffoldPage(
        title = "提示词包",
        onBack = onBack,
    ) {
        item(key = "pk_status_title") { SmallTitle("状态") }
        item(key = "pk_status") {
            PkCard {
            BasicComponent(
                title = if (manifest.value.enabled) "已启用" else "未启用",
                summary = status.value,
            )
            }
        }

        item(key = "pk_switch_title") { SmallTitle("开关") }
        item(key = "pk_enabled") {
            PkCard {
            BasicComponent(
                title = "启用提示词包",
                summary = if (manifest.value.enabled) {
                    "正文会作为系统提示注入（角色扮演时不注入），点一下关闭"
                } else {
                    "当前关闭，点一下打开"
                },
                onClick = { toggleMode(!manifest.value.enabled) },
            )
            }
        }
        item(key = "pk_keyword") {
            PkCard {
            BasicComponent(
                title = "仅关键词命中时注入",
                summary = if (manifest.value.keywordOnly) {
                    "当前：只有本轮输入包含关键词才注入，点一下改为每轮注入"
                } else {
                    "当前：每轮都注入，点一下改为仅关键词命中时注入"
                },
                onClick = { toggleKeywordOnly(!manifest.value.keywordOnly) },
            )
            }
        }

        item(key = "pk_body_title") { SmallTitle("正文") }
        item(key = "pk_body_editor") {
            PkCard {
            GlassTextField(
                state = bodyState,
                label = active?.name ?: "正文",
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 8, maxHeightInLines = 18),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            }
        }
        item(key = "pk_body_actions") {
            PkCard {
            val dirty = bodyState.text.toString() != savedBody
            BasicComponent(
                title = if (dirty) "保存正文（有未保存修改）" else "保存正文",
                summary = "当前 ${bodyState.text.length} 字" +
                    if (dirty) "，点一下写入" else "，与已保存内容一致",
                onClick = { if (dirty) saveBody() },
            )
            }
        }
        item(key = "pk_import") {
            PkCard {
            BasicComponent(
                title = "从文件导入正文",
                summary = "支持 txt / md；导入后只是填进编辑框，还要点一次「保存正文」",
                onClick = {
                    runCatching {
                        importer.launch(arrayOf("text/plain", "text/markdown", "*/*"))
                    }.onFailure { notice.value = "无法打开文件选择器" }
                },
            )
            }
        }
        notice.value?.let { message ->
            item(key = "pk_notice") {
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item(key = "pk_hint_title") { SmallTitle("其他改法") }
        item(key = "pk_hint") {
            PkCard {
            BasicComponent(
                title = "直接改追加文件",
                summary = "编辑 /sdcard/Download/eta_append.md 或应用私有 files/promptkit/append.md，" +
                    "按修改时间即时生效，无需重装",
            )
            }
        }
    }
}
