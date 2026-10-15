package io.github.mangi.eta.agent.model

/** 单个对话的上下文策略：压缩触发点与压缩轮数上限。 */
internal object AgentContextPolicy {
    /**
     * 输入用量达到窗口的该比例即触发自动压缩。
     * 从 0.85 降到 0.75：原来要等到快撑满才压，一轮对话往往只压到 1 次就再也不触发；
     * 提前压能正常用满 3 轮的额度，代价是每轮多一次摘要调用。
     */
    const val TRIGGER_RATIO = 0.75

    /** 单个对话最多允许的自动压缩轮数；压满即锁死，需新开对话继续。 */
    const val MAX_COMPACTION_ROUNDS = 3

    /** 已发生的压缩轮数：等于历史里的摘要条数。 */
    fun rounds(history: List<AgentModelClient.ConversationMessage>): Int {
        // 取记录过的最大轮次：编辑/重生成清掉旧摘要时，已压轮数不该往回退。
        val recorded = history.filter { it.contextSummary }.maxOfOrNull { it.compactionRound } ?: 0
        return maxOf(recorded, history.count { it.contextSummary })
    }

    /** 是否已压满轮数：此时对话只读。 */
    fun isLocked(history: List<AgentModelClient.ConversationMessage>): Boolean =
        rounds(history) >= MAX_COMPACTION_ROUNDS
}
