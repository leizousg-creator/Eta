package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 访问审批档位。
 *
 * 语义对齐上游三档：每次询问 / 按规则自动 / 完全允许。
 * 默认 [FULL_ACCESS]，与引入审批前的行为完全一致——装上不会突然卡住正在跑的任务。
 */
@Serializable
internal enum class AgentApprovalMode {
    /** 完全允许：不询问。 */
    FULL_ACCESS,

    /** 按规则自动：只在风险线上询问，其余放行。 */
    AUTO_APPROVE,

    /** 每次询问。 */
    ASK_EVERY,
    ;

    val label: String
        get() = when (this) {
            FULL_ACCESS -> "完全允许"
            AUTO_APPROVE -> "自动审批"
            ASK_EVERY -> "每次询问"
        }

    companion object {
        val DEFAULT = FULL_ACCESS

        fun fromName(raw: String?): AgentApprovalMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 审批设置。
 *
 * [waitMillis] 是单次审批的等待上限；超时按 APPROVAL_NOT_ANSWERED 处理，工具不执行。
 * [allowMainScreenReturn] 控制是否允许为了避免打断而跨屏把任务搬到主屏。
 */
@Serializable
internal data class AgentApprovalSettings(
    val mode: AgentApprovalMode = AgentApprovalMode.DEFAULT,
    val waitMillis: Int = DEFAULT_WAIT_MS,
    val allowMainScreenReturn: Boolean = false,
) {
    fun normalized(): AgentApprovalSettings = copy(
        waitMillis = waitMillis.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS),
    )

    val asksAnything: Boolean
        get() = mode != AgentApprovalMode.FULL_ACCESS

    companion object {
        const val DEFAULT_WAIT_MS = 120_000
        const val MIN_WAIT_MS = 15_000
        const val MAX_WAIT_MS = 600_000
    }
}
