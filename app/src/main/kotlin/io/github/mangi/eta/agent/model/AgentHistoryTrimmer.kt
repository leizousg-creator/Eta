package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 请求侧的确定性裁剪：只在这份要发给模型的历史上丢掉最老的若干「轮次」。
 * - 不调模型、不落库、不改界面；数据库与界面里的消息一条都不会少。
 * - 只在知道模型上下文窗口时才裁，且至少保留最近 [MIN_KEEP_ROUNDS] 轮。
 * - 以「用户轮次」为边界整轮丢，避免把工具调用与结果拆散。
 */
internal object AgentHistoryTrimmer {
    /** 无论预算多紧，都至少保留最近这么多轮。 */
    const val MIN_KEEP_ROUNDS = 10

    /** 上下文里留给历史的余量比例：其余位置给系统提示、记忆、工具 schema 与当前输入。 */
    private const val HISTORY_SHARE = 0.55

    /** 粗略 token 估算：CJK 按 1 字 1 token，其余字符按 4 字符 1 token。 */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code >= 0x2E80) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    /** 单条消息的估算：直接按它的 JSON 体积折算，含 content / tool_calls / 模型思考。 */
    private fun estimateMessageTokens(message: JSONObject?): Int =
        if (message == null) 0 else estimateTokens(message.toString())

    data class Outcome(
        val omittedMessages: Int = 0,
        val omittedUserRounds: Int = 0,
    )

    /**
     * 就地裁剪 [messages] 中 [systemCount, messages.length()-1) 之间的历史。
     * 最后一条是当前用户输入，永不裁；索引小于 [systemCount] 的系统消息永不裁。
     */
    fun trimInPlace(
        messages: JSONArray,
        systemCount: Int,
        contextWindow: Int?,
        reservedTokens: Int = 0,
        minKeepRounds: Int = MIN_KEEP_ROUNDS,
    ): Outcome {
        val window = contextWindow?.takeIf { it > 0 } ?: return Outcome()
        val historyEnd = messages.length() - 1
        if (historyEnd <= systemCount) return Outcome()
        val budget = (window * HISTORY_SHARE).toInt() - reservedTokens
        if (budget <= 0) return Outcome()

        val roundStarts = (systemCount until historyEnd)
            .filter { isUserTurn(messages.optJSONObject(it)) }
        if (roundStarts.size <= minKeepRounds) return Outcome()

        var used = 0
        for (index in systemCount until historyEnd) used += estimateMessageTokens(messages.optJSONObject(index))

        var droppedRounds = 0
        var dropCount = 0
        while (roundStarts.size - droppedRounds > minKeepRounds && used > budget) {
            val roundEnd = roundStarts.getOrNull(droppedRounds + 1) ?: break
            var dropped = 0
            for (index in systemCount + dropCount until roundEnd) {
                dropped += estimateMessageTokens(messages.optJSONObject(index))
            }
            dropCount = roundEnd - systemCount
            droppedRounds++
            used -= dropped
        }
        if (dropCount == 0) return Outcome()
        repeat(dropCount) { messages.remove(systemCount) }
        return Outcome(omittedMessages = dropCount, omittedUserRounds = droppedRounds)
    }

    private fun isUserTurn(message: JSONObject?): Boolean =
        message != null && message.optString("role") == "user" && !message.has("_eta_observation")
}
