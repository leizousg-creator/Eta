package io.github.mangi.eta.agent.model

/** 单个对话的上下文策略：压缩触发点与压缩轮数上限。 */
internal object AgentContextPolicy {
    /** 输入用量达到窗口的该比例即触发自动压缩。 */
    const val TRIGGER_RATIO = 0.85

    /** 单个对话最多允许的自动压缩轮数；压满即锁死，需新开对话继续。 */
    const val MAX_COMPACTION_ROUNDS = 3

    /** 已发生的压缩轮数：等于历史里的摘要条数。 */
    fun rounds(history: List<AgentModelClient.ConversationMessage>): Int =
        history.count { it.contextSummary }

    /** 是否已压满轮数：此时对话只读。 */
    fun isLocked(history: List<AgentModelClient.ConversationMessage>): Boolean =
        rounds(history) >= MAX_COMPACTION_ROUNDS
}
