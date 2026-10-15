package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec

/**
 * 会话内容搜索：对完整消息流做大小写不敏感匹配。
 * 标题与预览的匹配由调用方负责；notice 文案由调用方按当前语言注入。
 */
internal fun AgentChatHomeUiState.contentMatches(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): Boolean {
    if (query.isBlank()) return true
    return messages.any { message -> message.matches(query, noticeText) }
}

private fun AgentChatMessageUi.matches(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): Boolean = when (this) {
    is UserMessageUi -> {
        val prompt = AgentFileReferencePromptCodec.parse(content)
        prompt.request.contains(query, ignoreCase = true) ||
            prompt.references.any { reference ->
                reference.displayName.contains(query, ignoreCase = true) ||
                    reference.absolutePath.contains(query, ignoreCase = true)
            }
    }

    is AgentMessageUi -> content.contains(query, ignoreCase = true)

    is ThinkingMessageUi -> content.contains(query, ignoreCase = true)

    is ToolActivityMessageUi ->
        toolName.contains(query, ignoreCase = true) ||
            command?.contains(query, ignoreCase = true) == true ||
            argumentsSummary.contains(query, ignoreCase = true) ||
            resultSummary?.contains(query, ignoreCase = true) == true

    is ToolSummaryMessageUi -> tools.any { it.contains(query, ignoreCase = true) }

    is SystemNoticeMessageUi ->
        noticeText(code).contains(query, ignoreCase = true) ||
            detail?.contains(query, ignoreCase = true) == true

    else -> false
}

/** 会话搜索命中定位：打开会话后滚动到命中消息并临时高亮；消费后由调用方置空。 */
@Immutable
internal data class SearchJumpUiState(
    val messageId: String,
    /** 递增令牌：同一条消息被反复命中时也要能再次触发跳转。 */
    val token: Long,
)

/**
 * 第一条命中消息的 id。仅标题或预览命中、消息内容未命中时返回 null，
 * 此时调用方只打开会话、不做跳转。
 */
internal fun AgentChatHomeUiState.firstMatchMessageId(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): String? {
    if (query.isBlank()) return null
    return messages.firstOrNull { message -> message.matches(query, noticeText) }?.id
}
