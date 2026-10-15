package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.components.RoleplayChatUi

/** 上次运行被中断、可以从中断处继续时的提示状态；瞬时状态，不落库。 */
@Immutable
internal data class ResumableRunUiState(
    val runId: String,
    val interruptedAt: Long,
    /** 「上次停在哪 + 有没有动过手」；算不出来时为 null，横幅只显示原来的提示。 */
    val summary: String? = null,
)

/**
 * 给续跑横幅补一句实话：上次停在哪一步、有没有真的动过手机。
 * 只从检查点的对话记录里读，不做推断——没有记录就返回 null，不硬编一句。
 */
internal fun resumableRunSummary(transcript: List<AgentModelClient.ConversationMessage>): String? {
    if (transcript.isEmpty()) return null
    val last = transcript.lastOrNull { it.content.isNotBlank() || it.toolCallsJson.isNotBlank() }
    val stop = when {
        last == null -> "上次停在中间某一步"
        last.toolCallsJson.isNotBlank() -> "上次停在工具调用这一步"
        last.role == "user" -> "上次停在你的提问之后、还没开始回答"
        last.role == "assistant" -> "上次停在回复输出中途"
        else -> "上次停在中间某一步"
    }
    return "$stop，" + describeTouch(transcript.joinToString("\n") { it.toolCallsJson }) + "。"
}

/** 工具名 → 说人话的动作分组；摘要里最多说三组，多了省略。 */
private val RESUMABLE_TOOL_GROUPS = listOf(
    "点屏幕" to listOf(
        "tap", "tap_element", "tap_area", "long_press", "long_press_element", "swipe", "press_key",
    ),
    "输入或发送" to listOf("type_text", "set_clipboard"),
    "读写文件" to listOf(
        "read_file", "write_file", "edit_file", "stat_file", "list_directory", "glob_files", "grep_files",
    ),
    "跑命令" to listOf("terminal"),
    "打开应用" to listOf("launch_app", "open_uri"),
)

/** 数某个工具在工具调用 JSON 里出现几次；按带引号的完整名字匹配，tap 不会算进 tap_area。 */
private fun countToolCalls(text: String, tool: String): Int {
    if (text.isBlank()) return 0
    val token = "\"" + tool + "\""
    var count = 0
    var index = text.indexOf(token)
    while (index >= 0) {
        count++
        index = text.indexOf(token, index + token.length)
    }
    return count
}

/** 「有没有动过手」尽量说具体：分得出动作就点名，分不出就直说没认出来，不编。 */
private fun describeTouch(toolText: String): String {
    if (toolText.isBlank()) return "还没动过手机"
    val groups = RESUMABLE_TOOL_GROUPS
        .map { (label, tools) -> label to tools.sumOf { countToolCalls(toolText, it) } }
        .filter { it.second > 0 }
    if (groups.isEmpty()) return "已经动过手机（动作没认出来）"
    val head = groups.take(3).joinToString("、") { "${it.first} ${it.second} 次" }
    return "已经动过手机（" + head + (if (groups.size > 3) " 等" else "") + "）"
}

@Immutable
internal data class AgentChatUiState(
    val messages: List<AgentChatMessageUi>,
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
    // 完整脱敏历史独立于模型投影；摘要替换 history 时不覆盖 journal。
    val journal: List<AgentModelClient.ConversationMessage> = emptyList(),
    val input: String,
    val isStreaming: Boolean,
    val isCompacting: Boolean = false,
    val thinkingEnabled: Boolean,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.fromLegacy(thinkingEnabled),
    val availableReasoningEfforts: List<ReasoningEffort> = emptyList(),
    val pendingImages: List<PendingImageUi> = emptyList(),
    val pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    val appliedRuntimeRunIds: List<String> = emptyList(),
    val messageEdit: MessageEditUiState? = null,
    val roleplay: RoleplayBinding? = null,
    val roleplayMessages: RoleplayMessageState = RoleplayMessageState(),
    val lastModelUsage: TokenUsageUi? = null,
    /** 会话最近一次发送使用的本地 Model.id；null 表示尚未绑定，跟随默认模型。 */
    val modelId: String? = null,
    /** 会话搜索命中定位：打开会话后滚动到命中消息并临时高亮；瞬时状态，不落库。 */
    val searchJump: SearchJumpUiState? = null,
    /** 上次对话回合被中断、可以接着跑时的提示；瞬时状态，不落库。 */
    val resumableRun: ResumableRunUiState? = null,
) {
    val canCompactContext: Boolean get() = !isStreaming && messageEdit == null && history.any {
        !it.contextSummary && (it.role == "assistant" || it.role == "tool")
    }
}

@Immutable
sealed interface AgentChatMessageUi {
    val id: String
}

@Immutable
data class UserMessageUi(
    override val id: String,
    val content: String,
    val images: List<String> = emptyList(),
    val isEdited: Boolean = false,
) : AgentChatMessageUi

@Immutable
data class AgentMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean = false,
    val renderMarkdown: Boolean = true,
    val usage: TokenUsageUi? = null,
    val characterEditable: Boolean = false,
    val candidateCount: Int = 1,
    val selectedCandidate: Int = 0,
) : AgentChatMessageUi

enum class SystemNoticeCode(val wireValue: String) {
    Stopped("stopped"),
    EmptyResult("empty_result"),
    RuntimeFailed("runtime_failed"),
    ModelRetry("model_retry"),
    ContextCompaction("context_compaction"),
    Interrupted("interrupted");

    companion object {
        fun fromWireValue(value: String): SystemNoticeCode? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

/** Eta 自己生成的消息只保存稳定状态码，展示时再按当前语言解析。 */
@Immutable
data class SystemNoticeMessageUi(
    override val id: String,
    val code: SystemNoticeCode,
    val detail: String? = null,
    val contextTokens: Int? = null,
    /** 仅运行期存在的进行中标记，不随消息持久化；恢复的历史通知始终视为已结束。 */
    val running: Boolean = false,
) : AgentChatMessageUi

@Immutable
@kotlinx.serialization.Serializable
data class TokenUsageUi(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val requestDurationMs: Long? = null,
) {
    /** Request average includes time to the first token, but excludes tools and earlier retries. */
    val averageTokensPerSecond: Double?
        get() = outputTokens?.takeIf { it >= 0 }?.let { tokens ->
            requestDurationMs?.takeIf { it > 0 }?.let { tokens * 1000.0 / it }
        }

    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null
}

@Immutable
data class ThinkingMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean,
    val elapsedSeconds: Int? = null,
    val collapsed: Boolean = false,
) : AgentChatMessageUi

/**
 * 首页的 Run trace 入口卡片：展示 Agent 当前可调用的能力分组。
 */
@Immutable
data class RunTraceMessageUi(
    override val id: String,
    val capabilities: List<CapabilityUi>,
) : AgentChatMessageUi

@Immutable
data class CapabilityUi(
    val title: String,
    val items: List<String>,
)

/**
 * 工具调用摘要：出现在消息流中，显示当前/最近一步调用了哪些工具。
 */
@Immutable
data class ToolSummaryMessageUi(
    override val id: String,
    val tools: List<String>,
) : AgentChatMessageUi

@Immutable
data class ToolActivityMessageUi(
    override val id: String,
    val toolName: String,
    val status: ToolActivityStatusUi,
    val argumentsSummary: String,
    val command: String? = null,
    val resultSummary: String? = null,
    val imageCount: Int = 0,
) : AgentChatMessageUi

enum class ToolActivityStatusUi {
    Running,
    Success,
    Failed,
    Unknown,
}

/**
 * 建议语 chip 行。
 */
@Immutable
data class SuggestionChipsMessageUi(
    override val id: String,
    val prompts: List<String>,
) : AgentChatMessageUi

@Immutable
data class PendingImageUi(
    val id: String,
    val uri: String,
    val dataUrl: String,
    val mimeType: String,
)

@Immutable
data class PendingFileReferenceUi(
    val id: String,
    val reference: AgentFileReference,
)

@Immutable
data class MessageEditUiState(
    val targetMessageId: String,
    val previousInput: String,
    val previousImages: List<PendingImageUi>,
    val previousFileReferences: List<PendingFileReferenceUi>,
    val hasLaterTurns: Boolean,
    val preserveFollowingMessages: Boolean = false,
)

/** 角色对话在界面上的身份投影；普通对话返回 null。 */
internal fun AgentChatHomeUiState.roleplayChatUi(): RoleplayChatUi? = roleplay?.let {
    RoleplayChatUi(characterName = it.characterName, avatarPath = it.avatarPath, userName = it.userName)
}
