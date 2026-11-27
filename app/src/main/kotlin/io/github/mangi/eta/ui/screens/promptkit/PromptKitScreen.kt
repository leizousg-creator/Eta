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
import io.github.mangi.eta.data.promptkit.PromptKitManifest
import io.github.mangi.eta.data.promptkit.PromptKitTemplates
import io.github.mangi.eta.data.promptkit.PromptKitVersion
import io.github.mangi.eta.data.promptkit.SkillBundlePolicy
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.ScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 提示词包设置页（fork）。
 *
 * 对齐旧 fork 的完整能力：启用 / 关键词模式 / 仅技能包内技能 / 模板 / 版本切换与增删改 /
 * 正文编辑 / 文件导入。正文只存本机，不做任何网络请求，也不预置任何提示词内容。
 *
 * `replaceExtras` 字段在旧 fork 里也没有对应 UI（从未被读取），这里同样不暴露。
 */
@Composable
internal fun PromptKitScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val manifest = remember { mutableStateOf(PromptKitBridge.manifest()) }
    val status = remember { mutableStateOf(PromptKitBridge.statusLine()) }
    val bundleOnly = remember {
        mutableStateOf(
            PromptKitBridge.contextOrNull()?.let { ctx ->
                SkillBundlePolicy.isBundleOnly(File(ctx.filesDir, "skills"))
            } ?: true,
        )
    }
    val active: PromptKitVersion? = manifest.value.active()
    val savedBody = active?.body.orEmpty()
    val bodyState = remember { TextFieldState(savedBody) }
    val notice = remember { mutableStateOf<String?>(null) }
    val showRename = remember { mutableStateOf(false) }
    val keywordsState = remember {
        TextFieldState(manifest.value.keywords.joinToString("，"))
    }
    val renameState = remember { TextFieldState(active?.name.orEmpty()) }

    fun refresh(next: PromptKitManifest) {
        manifest.value = next
        status.value = PromptKitBridge.statusLine()
    }

    fun update(transform: (PromptKitManifest) -> PromptKitManifest) {
        refresh(PromptKitBridge.update(transform))
    }

    fun saveBody() {
        val id = active?.id
        if (id == null) {
            notice.value = "没有可写入的版本"
            return
        }
        val store = PromptKitBridge.storeOrNull()
        if (store == null) {
            notice.value = "提示词包存储未初始化"
            return
        }
        val text = bodyState.text.toString()
        runCatching { store.upsertVersion(id, body = text) }
            .onSuccess {
                refresh(PromptKitBridge.manifest())
                notice.value = "已保存 ${text.length} 字"
            }
            .onFailure { notice.value = "保存失败：${it.javaClass.simpleName}" }
    }

    fun insertTemplate(templateId: String) {
        val template = PromptKitTemplates.find(templateId) ?: return
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                if (template.body.isNotBlank()) {
                    template.body
                } else {
                    template.assetName?.let { name ->
                        runCatching {
                            context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
                        }.getOrNull()
                    }.orEmpty()
                }
            }
            if (body.isBlank()) {
                notice.value = "模板正文为空：${template.name}"
                return@launch
            }
            val store = PromptKitBridge.storeOrNull()
            if (store == null) {
                notice.value = "提示词包存储未初始化"
                return@launch
            }
            // 当前版本已有正文就另开一版，避免一键把用户写的东西冲掉
            val current = PromptKitBridge.manifest()
            val reusable = current.activeVersionId?.takeIf { (current.active()?.body?.length ?: 0) == 0 }
            val id = reusable ?: nextVersionId(current)
            store.upsertVersion(id = id, name = template.name, body = body)
            refresh(PromptKitBridge.update { it.copy(enabled = true, activeVersionId = id) })
            bodyState.setTextAndPlaceCursorAtEnd(body)
            notice.value = if (reusable == null) {
                "已用「${template.name}」新建版本 $id"
            } else {
                "已把「${template.name}」写入版本 $id"
            }
        }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
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

    if (showRename.value) {
        WindowDialog(
            show = true,
            title = "重命名版本",
            summary = "只改显示名，不动 id 与正文",
            onDismissRequest = { showRename.value = false },
        ) {
            GlassTextField(
                state = renameState,
                label = "版本名",
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DialogActions(
                confirmText = "保存",
                onCancel = { showRename.value = false },
                onConfirm = {
                    val id = active?.id
                    val name = renameState.text.toString().trim()
                    if (id != null && name.isNotEmpty()) {
                        update { current ->
                            current.copy(versions = current.versions.map { version ->
                                if (version.id == id) version.copy(name = name) else version
                            })
                        }
                        notice.value = "已改名为「$name」"
                    }
                    showRename.value = false
                },
            )
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
        item(key = "pk_switches") {
            PkCard {
                BasicComponent(
                    title = "启用提示词包",
                    summary = if (manifest.value.enabled) {
                        "正文会作为系统提示注入（角色扮演时不注入），点一下关闭"
                    } else {
                        "当前关闭，点一下打开"
                    },
                    onClick = { update { it.copy(enabled = !it.enabled) } },
                )
                BasicComponent(
                    title = "只在命中关键词时注入",
                    summary = if (manifest.value.keywordOnly) {
                        "当前：只有本轮输入包含关键词才注入，点一下改为每轮注入"
                    } else {
                        "当前：每轮都注入，点一下改为仅关键词命中时注入"
                    },
                    onClick = { update { it.copy(keywordOnly = !it.keywordOnly) } },
                )
                BasicComponent(
                    title = "仅使用技能包内技能",
                    summary = if (bundleOnly.value) {
                        "当前：只有技能包内技能参与索引与同步，点一下放开"
                    } else {
                        "当前：所有已启用技能都参与索引与同步，点一下收紧"
                    },
                    onClick = {
                        val next = !bundleOnly.value
                        val ctx = PromptKitBridge.contextOrNull()
                        if (ctx == null) {
                            notice.value = "上下文未初始化"
                        } else {
                            runCatching {
                                SkillBundlePolicy.setBundleOnly(File(ctx.filesDir, "skills"), next)
                            }.onSuccess {
                                bundleOnly.value = next
                                notice.value = if (next) {
                                    "已开启：只有技能包内技能参与索引与同步"
                                } else {
                                    "已关闭：所有已启用技能都会参与索引与同步"
                                }
                            }.onFailure { notice.value = "写入失败：${it.javaClass.simpleName}" }
                        }
                    },
                )
            }
        }

        item(key = "pk_keywords_title") { SmallTitle("关键词") }
        item(key = "pk_keywords") {
            PkCard {
                GlassTextField(
                    state = keywordsState,
                    label = "关键词（逗号、顿号或换行分隔）",
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 6),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val current = parseKeywords(keywordsState.text.toString())
                val saved = manifest.value.keywords
                BasicComponent(
                    title = if (current != saved) "保存关键词（有未保存修改）" else "保存关键词",
                    summary = if (current.isEmpty()) {
                        "当前没有关键词" + if (saved.isEmpty()) "" else "，点一下清空已保存的 ${saved.size} 个"
                    } else {
                        "共 ${current.size} 个：" + current.joinToString("、").take(60) +
                            if (current != saved) "，点一下写入" else "，已保存"
                    },
                    onClick = {
                        if (current != saved) {
                            update { it.copy(keywords = current) }
                            notice.value = "已保存 ${current.size} 个关键词"
                        }
                    },
                )
            }
        }

        item(key = "pk_template_title") { SmallTitle("模板") }
        item(key = "pk_templates") {
            PkCard {
                PromptKitTemplates.all.forEach { template ->
                    BasicComponent(
                        title = template.name,
                        summary = template.summary,
                        onClick = { insertTemplate(template.id) },
                    )
                }
            }
        }

        item(key = "pk_version_title") { SmallTitle("版本") }
        item(key = "pk_versions") {
            PkCard {
                if (manifest.value.versions.isEmpty()) {
                    BasicComponent(title = "还没有版本", summary = "点下面「新建版本」或在模板里选一个")
                } else {
                    manifest.value.versions.forEach { version ->
                        val isActive = version.id == manifest.value.activeVersionId
                        BasicComponent(
                            title = (if (isActive) "✓ " else "") + version.name,
                            summary = "${version.id} · ${version.body.length} 字" +
                                if (isActive) "（当前）" else "，点一下切换",
                            onClick = {
                                update { it.copy(enabled = true, activeVersionId = version.id) }
                                bodyState.setTextAndPlaceCursorAtEnd(version.body)
                                notice.value = "已切换到「${version.name}」"
                            },
                        )
                    }
                }
                BasicComponent(
                    title = "新建版本",
                    summary = "建一个空版本，正文在下面编辑",
                    onClick = {
                        val id = nextVersionId(PromptKitBridge.manifest())
                        PromptKitBridge.storeOrNull()?.upsertVersion(id = id, name = id, body = "")
                        update { it.copy(enabled = true, activeVersionId = id) }
                        bodyState.setTextAndPlaceCursorAtEnd("")
                        notice.value = "已新建版本 $id"
                    },
                )
                if (active != null) {
                    BasicComponent(
                        title = "重命名当前版本",
                        summary = "当前：${active.name}",
                        onClick = {
                            renameState.setTextAndPlaceCursorAtEnd(active.name)
                            showRename.value = true
                        },
                    )
                    BasicComponent(
                        title = "删除当前版本",
                        summary = "只删「${active.name}」，其他版本不受影响；删完会落到下一个版本",
                        onClick = {
                            val store = PromptKitBridge.storeOrNull()
                            if (store == null) {
                                notice.value = "提示词包存储未初始化"
                            } else {
                                runCatching { store.removeVersion(active.id) }
                                    .onSuccess {
                                        val next = PromptKitBridge.manifest()
                                        refresh(next)
                                        bodyState.setTextAndPlaceCursorAtEnd(next.active()?.body.orEmpty())
                                        notice.value = "已删除「${active.name}」"
                                    }
                                    .onFailure { notice.value = "删除失败：${it.javaClass.simpleName}" }
                            }
                        },
                    )
                }
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
                        runCatching { importer.launch(arrayOf("text/plain", "text/markdown", "*/*")) }
                            .onFailure { notice.value = "无法打开文件选择器" }
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

/** 关键词允许中英文逗号、顿号与换行分隔。 */
private fun parseKeywords(text: String): List<String> =
    text.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun nextVersionId(manifest: PromptKitManifest): String {
    var index = manifest.versions.size + 1
    while (manifest.versions.any { it.id == "v$index" }) index++
    return "v$index"
}

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
