package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 模型请求的重试预算。
 *
 * 只描述「失败后要不要再来一次、等多久」，分类与文案在 AgentModelFailure 里，
 * 这里不参与判断哪些失败可重试。
 */
@Serializable
internal data class AgentRetrySettings(
    val maxRetries: Int = 3,
    val baseDelayMs: Int = 2_000,
) {
    fun normalized(): AgentRetrySettings = copy(
        maxRetries = maxRetries.coerceIn(MIN_RETRIES, MAX_RETRIES),
        baseDelayMs = baseDelayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS),
    )

    companion object {
        const val MIN_RETRIES = 0
        const val MAX_RETRIES = 6
        const val MIN_DELAY_MS = 500
        const val MAX_DELAY_MS = 15_000
    }
}
