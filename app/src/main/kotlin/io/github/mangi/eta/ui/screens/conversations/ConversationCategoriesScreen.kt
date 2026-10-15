package io.github.mangi.eta.ui.screens.conversations

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.ConversationCategoryEntity
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话分类管理：新建、重命名、删除（只解散归属）与排序。
 * 会话本身的移动在会话长按菜单里完成，这里只管分类本身。
 */
@Composable
internal fun ConversationCategoriesScreen(
    categories: List<ConversationCategoryEntity>,
    conversationCategoryIds: Map<String, String>,
    onBack: () -> Unit,
    onCreate: (String) -> String?,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val counts = conversationCategoryIds.values.groupingBy { it }.eachCount()
    val ordered = remember(categories) {
        categories.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.conversation_categories_title),
        onBack = onBack,
    ) {
        item(key = "create") {
            EtaPreferenceGroup {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = stringResource(R.string.conversation_category_name_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = stringResource(R.string.conversation_category_new),
                    enabled = newName.isNotBlank(),
                    onClick = { if (onCreate(newName) != null) newName = "" },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        item(key = "list-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.conversation_categories_title))
        }
        if (ordered.isEmpty()) {
            item(key = "empty") {
                EtaPreferenceGroup {
                    Text(
                        stringResource(R.string.conversation_category_empty),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        items(ordered, key = { it.id }) { category ->
            val editing = editingId == category.id
            val pendingDelete = pendingDeleteId == category.id
            val index = ordered.indexOfFirst { it.id == category.id }
            EtaPreferenceGroup {
                when {
                    editing -> {
                        TextField(
                            value = editingName,
                            onValueChange = { editingName = it },
                            label = stringResource(R.string.conversation_category_name_hint),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            EtaTextButton(
                                text = stringResource(R.string.conversation_category_save),
                                enabled = editingName.isNotBlank(),
                                onClick = {
                                    onRename(category.id, editingName)
                                    editingId = null
                                },
                                modifier = Modifier.weight(1f),
                            )
                            EtaTextButton(
                                text = stringResource(R.string.conversation_category_cancel),
                                onClick = { editingId = null },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    pendingDelete -> {
                        Text(
                            stringResource(R.string.conversation_category_delete_confirm),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            EtaTextButton(
                                text = stringResource(R.string.conversation_category_delete),
                                onClick = {
                                    onDelete(category.id)
                                    pendingDeleteId = null
                                },
                                modifier = Modifier.weight(1f),
                            )
                            EtaTextButton(
                                text = stringResource(R.string.conversation_category_cancel),
                                onClick = { pendingDeleteId = null },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                category.name,
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.conversation_category_count, counts[category.id] ?: 0),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            EtaTextButton(
                                text = "↑",
                                enabled = index > 0,
                                minWidth = 0.dp,
                                onClick = { onMove(category.id, -1) },
                            )
                            EtaTextButton(
                                text = "↓",
                                enabled = index in 0 until ordered.lastIndex,
                                minWidth = 0.dp,
                                onClick = { onMove(category.id, 1) },
                            )
                            EtaTextButton(
                                text = stringResource(R.string.conversation_category_rename),
                                minWidth = 0.dp,
                                onClick = {
                                    editingId = category.id
                                    editingName = category.name
                                },
                            )
                            EtaTextButton(
                                text = stringResource(R.string.conversation_category_delete),
                                minWidth = 0.dp,
                                onClick = { pendingDeleteId = category.id },
                            )
                        }
                    }
                }
            }
        }
    }
}
