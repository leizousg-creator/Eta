package io.github.mangi.eta.ui.screens.promptkit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.model.PromptKitAction
import io.github.mangi.eta.ui.model.PromptKitUiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 提示词包设置页：开关、版本切换、正文与触发关键词。
 *
 * 正文只存在本机（filesDir/promptkit），这里不做任何网络请求，
 * 也不预置任何提示词内容。
 */
@Composable
internal fun PromptKitScreen(
    state: PromptKitUiState,
    onAction: (PromptKitAction) -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    MiuixScaffold(
        title = "提示词包",
        onBack = { onAction(PromptKitAction.NavigateBack) },
    ) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
                    .navigationBarsPadding()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(start = sidePadding, end = sidePadding),
                overscrollEffect = null,
            ) {
                item(key = "switch-title") { EtaPreferenceGroupTitle("总开关") }
                item(key = "switch-group") {
                    EtaPreferenceGroup(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        EtaSwitchPreference(
                            title = "启用提示词包",
                            summary = "开启后，当前版本的正文会作为一条系统消息注入每轮请求；关闭即恢复默认系统提示词",
                            checked = state.enabled,
                            enabled = !state.isLoading,
                            onCheckedChange = { onAction(PromptKitAction.ToggleEnabled(it)) },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaSwitchPreference(
                            title = "只在命中关键词时注入",
                            summary = "开启后，仅当本轮输入包含任一关键词才注入；关闭则每轮都注入",
                            checked = state.keywordOnly,
                            enabled = !state.isLoading && state.enabled,
                            onCheckedChange = { onAction(PromptKitAction.ToggleKeywordOnly(it)) },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaSwitchPreference(
                            title = "仅使用技能包内技能",
                            summary = "开启后只有随包技能参与索引与同步；后来新增的技能仍会出现在技能页，但不生效",
                            checked = state.bundleOnly,
                            enabled = !state.isLoading,
                            onCheckedChange = { onAction(PromptKitAction.ToggleBundleOnly(it)) },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = "当前状态",
                            summary = when {
                                state.isLoading -> "读取中…"
                                !state.enabled -> "未启用 · 正文不会进入请求"
                                else -> "已启用 · 版本 ${state.activeVersion?.name ?: "（无正文）"} · " +
                                    if (state.keywordOnly) "关键词模式" else "全程注入"
                            },
                        )
                    }
                }

                item(key = "versions-title") { EtaPreferenceGroupTitle("版本") }
                item(key = "versions-group") {
                    EtaPreferenceGroup(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        if (state.versions.isEmpty()) {
                            EtaPreference(
                                title = "还没有版本",
                                summary = "新建一个版本，然后在下面粘贴提示词正文",
                            )
                        } else {
                            state.versions.forEachIndexed { index, version ->
                                if (index > 0) EtaPreferenceDivider(hasLeading = false)
                                EtaPreference(
                                    title = if (version.id == state.activeVersionId) {
                                        "${version.name}（当前）"
                                    } else {
                                        version.name
                                    },
                                    summary = "${version.id} · ${version.chars} 字",
                                    enabled = !state.isLoading,
                                    onClick = { onAction(PromptKitAction.SelectVersion(version.id)) },
                                )
                            }
                        }
                        EtaPreferenceDivider(hasLeading = false)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EtaTextButton(
                                text = "新建版本",
                                enabled = !state.isLoading && !state.isSaving,
                                onClick = { onAction(PromptKitAction.CreateVersion) },
                                modifier = Modifier.weight(1f),
                            )
                            EtaTextButton(
                                text = "删除当前版本",
                                enabled = !state.isLoading && !state.isSaving && state.activeVersionId != null,
                                onClick = { showDeleteDialog = true },
                                modifier = Modifier.weight(1f),
                            )
                            EtaTextButton(
                                text = "重命名",
                                enabled = !state.isLoading && !state.isSaving && state.activeVersionId != null,
                                onClick = {
                                    renameText = state.activeVersion?.name ?: ""
                                    showRenameDialog = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                item(key = "body-title") { EtaPreferenceGroupTitle("正文") }
                item(key = "body-card") {
                    val bodyFilePicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri -> uri?.let { onAction(PromptKitAction.ImportBodyFile(it.toString())) } }
                    EtaCard(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            TextField(
                                value = state.draft,
                                onValueChange = { onAction(PromptKitAction.DraftChanged(it)) },
                                label = "提示词正文（只保存在本机）",
                                useLabelAsPlaceholder = true,
                                enabled = !state.isLoading && !state.isSaving,
                                minLines = 6,
                                maxLines = 14,
                                textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (state.hasUnsavedBody) "有未保存的修改" else "",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote1,
                                )
                                Text(
                                    text = "${state.draft.length} 字",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote1,
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            EtaTextButton(
                                text = "从文件导入（txt/md）",
                                enabled = !state.isLoading && !state.isSaving,
                                onClick = { bodyFilePicker.launch(arrayOf("text/*", "text/markdown", "text/plain")) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            EtaTextButton(
                                text = if (state.isSaving) "保存中…" else "保存正文",
                                enabled = state.canSaveBody,
                                onClick = { onAction(PromptKitAction.SaveBody) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }

                item(key = "keywords-title") { EtaPreferenceGroupTitle("触发关键词") }
                item(key = "keywords-card") {
                    EtaCard(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            TextField(
                                value = state.keywordsDraft,
                                onValueChange = { onAction(PromptKitAction.KeywordsChanged(it)) },
                                label = "用逗号或顿号分隔，例如：翻译、润色",
                                useLabelAsPlaceholder = true,
                                enabled = !state.isLoading && !state.isSaving,
                                minLines = 1,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            EtaTextButton(
                                text = "保存关键词",
                                enabled = state.canSaveKeywords,
                                onClick = { onAction(PromptKitAction.SaveKeywords) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                item(key = "notice-title") { EtaPreferenceGroupTitle("说明") }
                item(key = "notice-card") {
                    EtaPreferenceGroup(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 32.dp),
                    ) {
                        EtaPreference(
                            title = "内容由你自己提供",
                            summary = "应用不内置任何提示词正文；模型也可以通过 prompt_kit 工具读取和切换这些本地版本。" +
                                "提示词包会叠加在系统提示词上，属于你对自己设备行为负责的部分。",
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        EtaWindowDialog(
            show = true,
            title = "重命名版本",
            summary = "只改显示名；版本 id（${state.activeVersionId ?: ""}）与正文不变。",
            onDismissRequest = { showRenameDialog = false },
        ) {
            Column {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = "显示名",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = "保存",
                    confirmEnabled = !state.isSaving && renameText.isNotBlank(),
                    onCancel = { showRenameDialog = false },
                    onConfirm = {
                        state.activeVersionId?.let { id ->
                            onAction(PromptKitAction.RenameVersion(id, renameText.trim()))
                        }
                        showRenameDialog = false
                    },
                )
            }
        }
    }

    if (showDeleteDialog) {
        EtaWindowDialog(
            show = true,
            title = "删除版本",
            summary = "将删除版本 ${state.activeVersion?.name ?: ""} 及其正文，无法恢复。",
            onDismissRequest = { showDeleteDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = "删除",
                destructive = true,
                confirmEnabled = !state.isSaving,
                onCancel = { showDeleteDialog = false },
                onConfirm = {
                    showDeleteDialog = false
                    onAction(PromptKitAction.RemoveActiveVersion)
                },
            )
        }
    }

    state.notice?.let { notice ->
        EtaWindowDialog(
            show = true,
            title = "提示词包",
            summary = notice,
            onDismissRequest = { onAction(PromptKitAction.DismissNotice) },
        ) {
            EtaTextButton(
                text = "知道了",
                onClick = { onAction(PromptKitAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
