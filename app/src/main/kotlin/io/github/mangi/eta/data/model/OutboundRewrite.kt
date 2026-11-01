package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/** 一条出站替换规则：发送前把 [from] 换成 [to]，界面上仍显示原文。 */
@Serializable
data class OutboundRewriteRule(
    val from: String = "",
    val to: String = "",
)

/**
 * 出站改写设置。
 *
 * 只作用于发往模型的请求正文，会话记录、界面与工具结果都保持原文：
 * 上游按关键词拦请求时，改的是我们递出去的那份文本，用户看到的对话不受影响。
 */
@Serializable
data class OutboundRewriteSettings(
    val enabled: Boolean = false,
    val applyToSystem: Boolean = true,
    val rules: List<OutboundRewriteRule> = DEFAULT_RULES,
    val autoContinueEnabled: Boolean = true,
    val maxAutoContinues: Int = 3,
) {
    fun normalized(): OutboundRewriteSettings = copy(
        rules = rules.asSequence()
            .map { OutboundRewriteRule(from = it.from.trim(), to = it.to.trim()) }
            .filter { it.from.isNotEmpty() && it.from != it.to }
            .distinctBy { it.from }
            .take(MAX_RULES)
            .toList(),
        maxAutoContinues = maxAutoContinues.coerceIn(1, MAX_AUTO_CONTINUES),
    )

    val activeRules: List<OutboundRewriteRule>
        get() = if (enabled) rules.filter { it.from.isNotEmpty() && it.from != it.to } else emptyList()

    companion object {
        const val MAX_RULES = 64
        const val MAX_AUTO_CONTINUES = 5

        /** 默认只放两对最常见的刺头词，其余由用户自己加。 */
        val DEFAULT_RULES = listOf(
            OutboundRewriteRule("破解", "逆向"),
            OutboundRewriteRule("去除", "移除"),
        )
    }
}
