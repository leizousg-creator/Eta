package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
    val categories: List<Pair<String, String>> = emptyList(),
    /** 最近打开过的会话 id（最新在前）：侧栏最上面一栏用它，最多显示 3 条。 */
    val recentOpenedIds: List<String> = emptyList(),
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val updatedAtMillis: Long = 0L,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
    val characterName: String? = null,
    /** 所属会话分类；null 表示未分类。 */
    val categoryId: String? = null,
    val categoryName: String? = null,
)

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}
