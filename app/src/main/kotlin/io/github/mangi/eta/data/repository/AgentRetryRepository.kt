package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentRetryPolicy
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AgentRetrySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 模型请求重试预算：DataStore 真源 + 进程内快照供 Agent 循环同步读取。 */
internal object AgentRetryRepository {

    private val snapshot = MutableStateFlow(AgentRetrySettings())

    val stateFlow: StateFlow<AgentRetrySettings> = snapshot

    fun flow(): Flow<AgentRetrySettings> = SettingsDataStore.agentRetryFlow()

    suspend fun settings(): AgentRetrySettings = SettingsDataStore.agentRetry()

    suspend fun update(transform: (AgentRetrySettings) -> AgentRetrySettings) {
        val next = transform(snapshot.value).normalized()
        SettingsDataStore.setAgentRetry(next)
        publish(next)
    }

    fun publish(settings: AgentRetrySettings) {
        val normalized = settings.normalized()
        snapshot.value = normalized
        AgentRetryPolicy.publish(normalized)
    }
}
