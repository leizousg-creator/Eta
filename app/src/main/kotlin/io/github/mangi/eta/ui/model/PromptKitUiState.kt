package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/** 提示词包页上列出的一个版本。 */
@Immutable
internal data class PromptKitVersionItem(
    val id: String,
    val name: String,
    val chars: Int,
)

/** 提示词包页的只读状态；读写都经 PromptKitBridge。 */
@Immutable
internal data class PromptKitUiState(
    val enabled: Boolean = false,
    val keywordOnly: Boolean = false,
    /** 仅使用技能包内技能。 */
    val bundleOnly: Boolean = true,
    val keywords: List<String> = emptyList(),
    val versions: List<PromptKitVersionItem> = emptyList(),
    val activeVersionId: String? = null,
    val draft: String = "",
    val savedBody: String = "",
    val keywordsDraft: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val notice: String? = null,
) {
    val activeVersion: PromptKitVersionItem? get() = versions.firstOrNull { it.id == activeVersionId }
    val hasUnsavedBody: Boolean get() = draft != savedBody
    val canSaveBody: Boolean get() = !isLoading && !isSaving && hasUnsavedBody
    val keywordsDraftList: List<String> get() = parsePromptKitKeywords(keywordsDraft)
    val hasUnsavedKeywords: Boolean get() = keywordsDraftList != keywords
    val canSaveKeywords: Boolean get() = !isLoading && !isSaving && hasUnsavedKeywords
}

/** 关键词输入允许中英文逗号、顿号和换行分隔。 */
internal fun parsePromptKitKeywords(text: String): List<String> =
    text.split(',', '，', '、', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
