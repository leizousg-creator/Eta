package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.approval.AgentApprovalPolicy
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AgentApprovalSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 审批档位：DataStore 真源 + 进程内快照供工具执行线程同步读取。
 *
 * 和 [AgentRetryRepository] 同一套形状：工具执行发生在后台同步代码里，
 * 不能每调一次工具都去 await DataStore，所以用快照 + [AgentApprovalPolicy.publish] 推送。
 */
internal object AgentApprovalRepository {

    private val snapshot = MutableStateFlow(AgentApprovalSettings())

    val stateFlow: StateFlow<AgentApprovalSettings> = snapshot

    fun flow(): Flow<AgentApprovalSettings> = SettingsDataStore.agentApprovalFlow()

    suspend fun settings(): AgentApprovalSettings = SettingsDataStore.agentApproval()

    suspend fun update(transform: (AgentApprovalSettings) -> AgentApprovalSettings) {
        val next = transform(snapshot.value).normalized()
        SettingsDataStore.setAgentApproval(next)
        publish(next)
    }

    suspend fun setMode(mode: io.github.mangi.eta.data.model.AgentApprovalMode) {
        update { it.copy(mode = mode) }
    }

    fun publish(settings: AgentApprovalSettings) {
        val normalized = settings.normalized()
        snapshot.value = normalized
        AgentApprovalPolicy.publish(normalized)
    }
}
