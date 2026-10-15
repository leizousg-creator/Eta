package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire

/** 用持久 checkpoint、终态 outbox 与 Runtime 活跃状态共同判定恢复动作。 */
internal object AgentRunRecoveryCoordinator {
    data class Completed(
        val result: AgentRuntimeWire.CompletedRun,
        val checkpoint: AgentRunCheckpointStore.Checkpoint?,
    )

    data class Plan(
        val completed: List<Completed>,
        val reattach: AgentRunCheckpointStore.Checkpoint?,
        val interrupted: List<AgentRunCheckpointStore.Checkpoint>,
    )

    fun plan(
        checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
        completedRuns: List<AgentRuntimeWire.CompletedRun>,
        activeStateKnown: Boolean,
        terminalStateKnown: Boolean,
        activeRunId: String?,
        locallyObservedRunId: String?,
        activeRunIds: Set<String> = emptySet(),
    ): Plan {
        val activeIds = (activeRunIds + listOfNotNull(activeRunId))
            .filter { runId -> runId.isNotBlank() }
            .toSet()
        val uiCheckpoints = checkpoints
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .associateBy { it.runId }
        val completed = completedRuns
            .asSequence()
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .filterNot { it.stableRunId == locallyObservedRunId }
            .sortedBy { it.createdAt }
            .map { run -> Completed(run, uiCheckpoints[run.stableRunId]) }
            .toList()
        val completedRunIds = completed.mapTo(mutableSetOf()) { it.result.stableRunId }
        val unresolved = uiCheckpoints.values
            .filterNot { it.runId == locallyObservedRunId || it.runId in completedRunIds }
            .sortedBy { it.createdAt }
        // 多开时活跃会话不止一个：集合里的每一个都还在跑，都不能当"中断"处理。
        val active = unresolved
            .takeIf { activeStateKnown }
            ?.firstOrNull { checkpoint -> checkpoint.runId in activeIds }

        return Plan(
            completed = completed,
            reattach = active,
            interrupted = if (activeStateKnown && terminalStateKnown) {
                unresolved.filterNot { checkpoint -> checkpoint.runId in activeIds }
            } else {
                emptyList()
            },
        )
    }

    internal val AgentRuntimeWire.CompletedRun.stableRunId: String
        get() = result.runId.ifBlank { handoff.id }
}
