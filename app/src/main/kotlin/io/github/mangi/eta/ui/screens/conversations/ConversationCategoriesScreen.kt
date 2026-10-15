package io.github.mangi.eta.ui.screens.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import androidx.compose.foundation.layout.size
import io.github.mangi.eta.data.db.ConversationCategoryEntity
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话分类管理：新建、重命名、删除（只解散归属）与排序。
 * 分类行可展开查看其下的会话（点会话直接打开）；顶部可搜索会话，命中项会在列表上方集中列出。
 */
@Composable
internal fun ConversationCategoriesScreen(
    categories: List<ConversationCategoryEntity>,
    conversationCategoryIds: Map<String, String>,
    conversations: List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onCreate: (String) -> String?,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val newNameFocus = remember { FocusRequester() }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(creating) { if (creating) runCatching { newNameFocus.requestFocus() } }
    val counts = conversationCategoryIds.values.groupingBy { it }.eachCount()
    val ordered = remember(categories) {
        categories.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
    }
    val keyword = query.trim()
    val hits = remember(conversations, keyword) {
        if (keyword.isEmpty()) {
            emptyList()
        } else {
            conversations.filter {
                it.title.contains(keyword, ignoreCase = true) || it.preview.contains(keyword, ignoreCase = true)
            }.sortedByDescending { it.updatedAtMillis }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.conversation_categories_title),
        onBack = onBack,
    ) {
        item(key = "search") {
            EtaPreferenceGroup {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.conversation_search_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (keyword.isNotEmpty()) {
            item(key = "search-title") {
                EtaPreferenceGroupTitle("搜索会话 · 命中 ${hits.size} 条")
            }
            if (hits.isEmpty()) {
                item(key = "search-empty") {
                    EtaPreferenceGroup {
                        Text(
                            "没有匹配的会话",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            items(hits, key = { "hit-" + it.id }) { conversation ->
                EtaPreferenceGroup {
                    ConversationLine(conversation, onClick = { onOpenConversation(conversation.id) })
                }
            }
        }
        if (keyword.isEmpty()) {
            item(key = "list-title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.conversation_categories_title),
                        style = MiuixTheme.textStyles.headline2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.conversation_category_new),
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { creating = !creating }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            if (creating) {
                item(key = "create") {
                    EtaPreferenceGroup {
                        TextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = stringResource(R.string.conversation_category_name_hint),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(newNameFocus),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            EtaTextButton(
                                text = stringResource(R.string.conversation_category_new),
                                enabled = newName.isNotBlank(),
                                onClick = {
                                    if (onCreate(newName) != null) {
                                        newName = ""
                                        creating = false
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            EtaTextButton(
                                text = stringResource(R.string.conversation_category_cancel),
                                onClick = {
                                    newName = ""
                                    creating = false
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
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
                val expanded = expandedId == category.id
                val inside = if (expanded) {
                    conversations.filter { it.categoryId == category.id }.sortedByDescending { it.updatedAtMillis }
                } else {
                    emptyList()
                }
                val canUp = index > 0
                val canDown = index in 0 until ordered.lastIndex
                var menuOpen by remember { mutableStateOf(false) }
                val menuOffsetY = with(LocalDensity.current) { 4.dp.roundToPx() }
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
                            val toggle = {
                                expandedId = if (expanded) null else category.id
                            }
                            Column(Modifier.fillMaxWidth()) {
                                Box(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (expanded) "▾" else "▸",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(end = 4.dp).clickable(onClick = toggle),
                                    )
                                    Text(
                                        category.name,
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f).clickable(onClick = toggle),
                                    )
                                    Text(
                                        stringResource(R.string.conversation_category_count, counts[category.id] ?: 0),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    Text(
                                        "⋯",
                                        fontSize = 42.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier
                                            .clickable { menuOpen = true }
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                if (menuOpen) {
                                    Popup(
                                        alignment = Alignment.BottomEnd,
                                        offset = IntOffset(0, menuOffsetY),
                                        onDismissRequest = { menuOpen = false },
                                        properties = PopupProperties(focusable = true),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                                                .padding(vertical = 4.dp),
                                        ) {
                                            Column(Modifier.width(150.dp)) {
                                                CategoryMenuRow("上移", enabled = canUp) {
                                                    menuOpen = false
                                                    onMove(category.id, -1)
                                                }
                                                CategoryMenuRow("下移", enabled = canDown) {
                                                    menuOpen = false
                                                    onMove(category.id, 1)
                                                }
                                                CategoryMenuRow(stringResource(R.string.conversation_category_rename)) {
                                                    menuOpen = false
                                                    editingId = category.id
                                                    editingName = category.name
                                                }
                                                CategoryMenuRow(stringResource(R.string.conversation_category_delete), danger = true) {
                                                    menuOpen = false
                                                    pendingDeleteId = category.id
                                                }
                                            }
                                        }
                                    }
                                }
                                }
                                if (expanded) {
                                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp)) {
                                        if (inside.isEmpty()) {
                                            Text(
                                                "这个分类下还没有会话，在会话长按菜单里可以移进来",
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                modifier = Modifier.padding(bottom = 4.dp),
                                            )
                                        }
                                        inside.forEach { conversation ->
                                            ConversationLine(
                                                conversation = conversation,
                                                onClick = { onOpenConversation(conversation.id) },
                                                showCategory = false,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationLine(
    conversation: ConversationSummaryUi,
    onClick: () -> Unit,
    showCategory: Boolean = true,
) {
    val subtitle = listOfNotNull(
        if (showCategory) conversation.categoryName ?: "未分类" else null,
        conversation.timeLabel.takeIf { it.isNotBlank() },
        conversation.preview.take(30).takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        Text(
            conversation.title.ifBlank { "（无标题）" },
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 分类「⋯」弹出菜单里的一行：40dp 高、左对齐、无额外间距。 */
@Composable
private fun CategoryMenuRow(
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.body2,
            color = when {
                danger -> MiuixTheme.colorScheme.error
                enabled -> MiuixTheme.colorScheme.onSurface
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
    }
}
