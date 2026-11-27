package io.github.mangi.eta.ui.screens.promptkit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.data.promptkit.PromptKitBridge
import io.github.mangi.eta.ui.components.ScaffoldPage
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 提示词包设置页（fork 精简版）。
 *
 * 与旧 fork 的完整版相比省掉了版本管理、模板插入与文件导入：那些是配置便利性，
 * 而真正影响使用的三件事这里都能做——开关、关键词模式、以及正文的即时生效。
 *
 * 正文刻意不在这里编辑：`files/promptkit/append.md` 与 `/sdcard/Download/eta_append.md`
 * 支持按修改时间热加载，用文件管理器改完就生效，比在 App 里做富文本安全得多。
 */
@Composable
internal fun PromptKitScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val manifest = remember { mutableStateOf(PromptKitBridge.manifest()) }
    val status = remember { mutableStateOf(PromptKitBridge.statusLine()) }
    val bodyPreview = remember { mutableStateOf(currentBodyPreview()) }

    fun persist(next: io.github.mangi.eta.data.promptkit.PromptKitManifest) {
        PromptKitBridge.update { next }
        manifest.value = next
        status.value = PromptKitBridge.statusLine()
        bodyPreview.value = currentBodyPreview()
    }

    ScaffoldPage(
        title = "提示词包",
        onBack = onBack,
    ) {
        item(key = "promptkit_status_title") {
            SmallTitle("状态")
        }
        item(key = "promptkit_status") {
            BasicComponent(
                title = manifest.value.let { if (it.enabled) "已启用" else "未启用" },
                summary = status.value,
            )
        }

        item(key = "promptkit_switches_title") {
            SmallTitle("开关")
        }
        item(key = "promptkit_enabled") {
            BasicComponent(
                title = "启用提示词包",
                summary = "打开后，提示词包正文会作为系统提示注入；角色扮演时不注入",
                onClick = { persist(manifest.value.copy(enabled = !manifest.value.enabled)) },
            )
        }
        item(key = "promptkit_keyword") {
            BasicComponent(
                title = "仅关键词命中时注入",
                summary = if (manifest.value.keywordOnly) {
                    "当前：只有本轮输入包含关键词才注入"
                } else {
                    "当前：每轮都注入"
                },
                onClick = { persist(manifest.value.copy(keywordOnly = !manifest.value.keywordOnly)) },
            )
        }

        item(key = "promptkit_body_title") {
            SmallTitle("正文")
        }
        item(key = "promptkit_body") {
            Text(
                text = bodyPreview.value.ifBlank { "（正文为空）" },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackground,
            )
        }
        item(key = "promptkit_hint") {
            BasicComponent(
                title = "改正文不用进这里",
                summary = "直接编辑 /sdcard/Download/eta_append.md 或应用私有 files/promptkit/append.md，" +
                    "保存后按修改时间即时生效，无需重装",
            )
        }
    }
}

/** 正文预览：优先 active 版本，其次追加文件。 */
private fun currentBodyPreview(): String {
    val store = PromptKitBridge.storeOrNull() ?: return ""
    val body = store.active()?.body?.takeIf { it.isNotBlank() }
    if (body != null) {
        return body.take(1200)
    }
    val append = PromptKitBridge.contextOrNull()?.let { ctx ->
        File(ctx.filesDir, "promptkit/append.md").takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
    }
    return append?.take(1200).orEmpty()
}
