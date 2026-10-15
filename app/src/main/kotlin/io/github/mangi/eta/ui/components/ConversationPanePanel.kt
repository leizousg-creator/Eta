package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup

private object ConversationPanelMetrics {
    val PaneHorizontalPadding = 16.dp
    val TopInset = 6.dp
    val AfterActionBar = 8.dp
    val BottomInset = 8.dp
    val ActionIconSize = 20.dp
    val RowMinHeight = 48.dp
    val RowGap = 4.dp
    val RowCornerRadius = 12.dp
    val RowHorizontalPadding = 12.dp
    val RowVerticalPadding = 12.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 10.dp
    val EmptyVerticalPadding = 28.dp
    val DockTopGap = 8.dp
}

/**
 * 侧栏与外层推移容器共用的背景色。
 *
 * 深色下聊天区保持 surface（纯黑/近黑），侧栏沿用同一颜色会在展开后与聊天区融为一体；
 * 抬高一档到 surfaceContainer，保证两侧始终可分。浅色维持 surface，避免改变现有观感。
 */
@Composable
internal fun conversationPaneContainerColor(): Color {
    val colors = MiuixTheme.colorScheme
    return if (colors.background.luminance() > 0.5f) colors.surface else colors.surfaceContainer
}

@Composable
internal fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSearchChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onMoveToCategory: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onConversationPin: (ConversationSummaryUi) -> Unit,
    onBatchDelete: (List<ConversationSummaryUi>) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRootSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // state.conversations 已由 AgentAppState 按标题、预览与消息内容过滤。
    val query = state.searchQuery.trim()
    var batchMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var collapsedSections by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<String>()) }
    val capabilities = rememberDeviceCapabilities()
    val groups = remember(state.conversations, state.categories) { state.conversations.groupForDrawer(state.categories) }

    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight(),
        color = conversationPaneContainerColor(),
        contentColor = MiuixTheme.colorScheme.onSurface,
    ) {
        // 顶部品牌与底部搜索固定，历史列表在中间滚动。
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            PaneFixedRegion {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                ) {
                    Spacer(Modifier.height(ConversationPanelMetrics.TopInset))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, tint = Color.Unspecified,
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(MiuixTheme.colorScheme.surfaceContainerHighest).padding(4.dp))
                        Row(
                            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.app_name),
                                style = MiuixTheme.textStyles.headline2,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            PaneModeBadges(
                                rootGranted = capabilities.root.isGranted,
                                accessibilityAvailable = capabilities.accessibilityAvailable,
                                onOpenRootSettings = onOpenRootSettings,
                                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                                onOpenSettings = onOpenSettings,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(ConversationPanelMetrics.AfterActionBar))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    )
                    .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding)
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(ConversationPanelMetrics.RowGap),
                overscrollEffect = null,
            ) {
                item(key = "history-heading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 4.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (batchMode) "已选 ${selectedIds.size} 项" else stringResource(R.string.action_conversation_history),
                            style = MiuixTheme.textStyles.body2,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        if (batchMode) {
                            Text("全选", color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp).clickable {
                                    selectedIds = state.conversations.filterNot { it.isPinned || state.categories.any { c -> c.first == it.categoryId } }.map { it.id }.toSet()
                                })
                            Text("取消", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp).clickable {
                                    batchMode = false; selectedIds = emptySet()
                                })
                            Text("删除", color = if (selectedIds.isEmpty()) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp).clickable(enabled = selectedIds.isNotEmpty()) {
                                    onBatchDelete(state.conversations.filter { it.id in selectedIds && !it.isPinned })
                                    batchMode = false; selectedIds = emptySet()
                                })
                        } else {
                            Text("批量删除", color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp).clickable { batchMode = true; selectedIds = emptySet() })
                        }
                    }
                }
                if (state.conversations.isEmpty()) {
                    item {
                        EmptyConversations(isSearching = query.isNotBlank())
                    }
                } else {
                    groups.forEach { group ->
                        val sectionKey = group.section.storageKey()
                        val collapsed = sectionKey in collapsedSections
                        item(key = "section-$sectionKey") {
                            ConversationSectionHeader(
                                group = group,
                                collapsed = collapsed,
                                onToggle = {
                                    collapsedSections = if (collapsed) collapsedSections - sectionKey else collapsedSections + sectionKey
                                },
                                // 分类分组标题上的入口只进入多选（不预勾任何一条），整组删除不再有一键路径
                                onDeleteCategory = if (group.section is ConversationDrawerSection.Category) {
                                    { batchMode = true; selectedIds = emptySet() }
                                } else null,
                            )
                        }
                        if (collapsed) return@forEach
                        val visibleItems = group.items
                        items(
                            items = visibleItems,
                            key = { it.id },
                        ) { conversation ->
                            ConversationTextRow(
                                conversation = conversation,
                                selected = conversation.id == state.selectedConversationId,
                                batchMode = batchMode,
                                checked = conversation.id in selectedIds,
                                onClick = {
                                    if (batchMode) {
                                        selectedIds = if (conversation.id in selectedIds) selectedIds - conversation.id else selectedIds + conversation.id
                                    } else {
                                        onConversationSelected(conversation.id)
                                    }
                                },
                                onRename = { onConversationRename(conversation) },
                                onExport = { onConversationExport(conversation) },
                                onDelete = { onConversationDelete(conversation) },
                                onPin = { onConversationPin(conversation) },
                                onMoveToCategory = { onMoveToCategory(conversation) },
                            )
                        }
                    }
                }
            }
            PaneFixedRegion {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                ) {
                    Spacer(Modifier.height(ConversationPanelMetrics.DockTopGap))
                    PaneSearchAndSettings(
                        query = state.searchQuery,
                        onSearchChange = onSearchChange,
                        onOpenSettings = onOpenSettings,
                    )
                    Spacer(Modifier.height(ConversationPanelMetrics.BottomInset))
                }
            }
        }
    }
}

@Composable
private fun PaneFixedRegion(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(conversationPaneContainerColor()),
    ) {
        content()
    }
}

@Composable
private fun PaneSearchAndSettings(
    query: String,
    onSearchChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchBar(
            modifier = Modifier.weight(1f),
            insideMargin = DpSize.Zero,
            expanded = false,
            onExpandedChange = {},
            inputField = {
                InputField(
                    query = query,
                    onQueryChange = onSearchChange,
                    onSearch = onSearchChange,
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(R.string.conversation_search_hint),
                    // 深色下侧栏已抬高到 surfaceContainer，搜索框需再高一档才能显出轮廓；
                    // 浅色的 surfaceContainerHigh 与 surfaceContainerHighest 相同，观感不变。
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                )
            },
            content = {},
        )
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(48.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHighest),
        ) {
            Icon(MiuixIcons.Settings, contentDescription = stringResource(R.string.sidebar_settings), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun PaneModeBadges(
    rootGranted: Boolean,
    accessibilityAvailable: Boolean,
    onOpenRootSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (rootGranted) PaneModeBadge(stringResource(R.string.sidebar_mode_root), onClick = onOpenRootSettings)
        if (!rootGranted && !accessibilityAvailable) PaneModeBadge(stringResource(R.string.sidebar_mode_basic), onClick = onOpenSettings, active = false)
    }
}

@Composable
private fun PaneModeBadge(label: String, onClick: () -> Unit, active: Boolean = true) {
    val description = stringResource(R.string.sidebar_mode_description, label)
    val color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Text(
        text = label,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f))
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.sidebar_settings), onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = color,
        style = MiuixTheme.textStyles.footnote1,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onDeleteCategory: (() -> Unit)?,
) {
    val headerLabel = group.localizedLabel()
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (collapsed) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                ),
        )
        Text(headerLabel, color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() })
        Text(
            if (collapsed) "▸" else "▾",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1, fontSize = 12.sp,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTextRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    batchMode: Boolean = false,
    checked: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onMoveToCategory: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConversationPanelMetrics.RowMinHeight)
                .clip(RoundedCornerShape(ConversationPanelMetrics.RowCornerRadius))
                .background(
                    if (selected) {
                        // 与侧栏背景保持一档亮度差；浅色下与 surfaceContainerHigh 相同。
                        MiuixTheme.colorScheme.surfaceContainerHighest
                    } else {
                        Color.Transparent
                    },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        showActionMenu = true
                    },
                )
                .padding(
                    horizontal = ConversationPanelMetrics.RowHorizontalPadding,
                    vertical = ConversationPanelMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (batchMode) {
                Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off, onClick = onClick, modifier = Modifier.padding(end = 4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
            val title = conversation.title.ifBlank { conversation.preview }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (conversation.isPinned) {
                Icon(
                    imageVector = MiuixIcons.Pin,
                    contentDescription = stringResource(R.string.conversation_pin_marker),
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                modifier = Modifier.weight(1f),
                text = title,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            }
            // 标题与角色名相同（如未改名的角色会话）时不再重复第二行。
            conversation.characterName?.takeIf { it != title }?.let { name ->
                Text(name, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            }
            if (conversation.isActiveRun) {
                Box(
                    modifier = Modifier
                        .padding(start = ConversationPanelMetrics.ActiveDotGap)
                        .size(ConversationPanelMetrics.ActiveDotSize)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
        }

        WindowListPopup(
            show = showActionMenu,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            alignment = PopupPositionProvider.Align.BottomEnd,
            onDismissRequest = { showActionMenu = false },
        ) {
            val pinText = stringResource(if (conversation.isPinned) R.string.action_unpin else R.string.action_pin)
            val moveText = stringResource(R.string.conversation_category_move)
            val renameText = stringResource(R.string.action_rename)
            val exportText = stringResource(R.string.action_export)
            val deleteText = stringResource(R.string.action_delete)
            val moveItem = remember(moveText) {
                DropdownItem(
                    text = moveText,
                    icon = { modifier ->
                        Icon(
                            imageVector = MiuixIcons.Notes,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }

            val renameItem = remember(renameText) {
                DropdownItem(
                    text = renameText,
                    icon = { modifier ->
                        Icon(
                            imageVector = MiuixIcons.Rename,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val exportItem = remember(exportText) {
                DropdownItem(
                    text = exportText,
                    icon = { modifier ->
                        Icon(
                            imageVector = MiuixIcons.Download,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val deleteItem = remember(deleteText) {
                DropdownItem(
                    text = deleteText,
                    icon = { modifier ->
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                            tint = MiuixTheme.colorScheme.error,
                        )
                    },
                )
            }
            val pinItem = remember(pinText, conversation.isPinned) {
                DropdownItem(
                    text = pinText,
                    icon = { modifier ->
                        Icon(
                            imageVector = if (conversation.isPinned) MiuixIcons.Unpin else MiuixIcons.Pin,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                )
            }
            val deleteColors = DropdownDefaults.dropdownColors(
                contentColor = MiuixTheme.colorScheme.error,
                selectedContentColor = MiuixTheme.colorScheme.error,
                selectedIndicatorColor = MiuixTheme.colorScheme.error,
            )
            ListPopupColumn {
                DropdownImpl(
                    item = pinItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onPin()
                    },
                )
                DropdownImpl(
                    item = moveItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 1,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onMoveToCategory()
                    },
                )

                DropdownImpl(
                    item = renameItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 2,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onRename()
                    },
                )
                DropdownImpl(
                    item = exportItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 3,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onExport()
                    },
                )
                DropdownImpl(
                    item = deleteItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 4,
                    dropdownColors = deleteColors,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    Text(
        text = stringResource(
            if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            horizontal = ConversationPanelMetrics.RowHorizontalPadding,
            vertical = ConversationPanelMetrics.EmptyVerticalPadding,
        ),
    )
}

@Composable
private fun PaneShortcut(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .clickable(onClickLabel = label, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurface)
        Text(label, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
)

private sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
    data class Category(val id: String?, val name: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
    is ConversationDrawerSection.Category -> value.name.ifBlank { stringResource(R.string.conversation_category_uncategorized) }
}

private fun ConversationDrawerSection.storageKey(): String = when (this) {
    ConversationDrawerSection.Pinned -> "pinned"
    ConversationDrawerSection.Today -> "today"
    is ConversationDrawerSection.Dated -> "dated:$label"
    is ConversationDrawerSection.Category -> "category:${id ?: "-"}"
}

private fun List<ConversationSummaryUi>.groupForDrawer(categories: List<Pair<String, String>>): List<ConversationDrawerGroup> {
    // 有会话归属分类时按「置顶 / 各分类 / 未分类」分组，否则回退到时间分组。
    val known = categories.toMap()
    if (known.isNotEmpty() && any { it.categoryId != null && known.containsKey(it.categoryId) }) {
        val categorizedGroups = mutableListOf<ConversationDrawerGroup>()
        val pinnedItems = filter { it.isPinned }
        if (pinnedItems.isNotEmpty()) {
            categorizedGroups += ConversationDrawerGroup(ConversationDrawerSection.Pinned, pinnedItems)
        }
        val rest = filterNot { it.isPinned }
        categories.forEach { (categoryId, categoryName) ->
            val items = rest.filter { it.categoryId == categoryId }
            if (items.isNotEmpty()) {
                categorizedGroups += ConversationDrawerGroup(
                    ConversationDrawerSection.Category(categoryId, categoryName),
                    items,
                )
            }
        }
        val loose = rest.filter { it.categoryId == null || !known.containsKey(it.categoryId) }
        if (loose.isNotEmpty()) {
            categorizedGroups += ConversationDrawerGroup(ConversationDrawerSection.Category(null, ""), loose)
        }
        return categorizedGroups
    }
    if (isEmpty()) return emptyList()
    val groups = mutableListOf<ConversationDrawerGroup>()
    for (conversation in this) {
        val section = conversation.drawerSection()
        val last = groups.lastOrNull()
        if (last?.section == section) {
            groups[groups.lastIndex] = last.copy(items = last.items + conversation)
        } else {
            groups += ConversationDrawerGroup(section = section, items = listOf(conversation))
        }
    }
    return groups
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isActiveRun || isUpdatedToday(updatedAtMillis) -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

private fun isUpdatedToday(timestampMillis: Long): Boolean {
    if (timestampMillis <= 0L) return true
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return now.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
}

