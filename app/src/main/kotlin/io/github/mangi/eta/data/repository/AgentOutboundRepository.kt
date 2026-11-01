package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.OutboundRewriteSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * 出站改写设置。
 *
 * Agent 循环是同步阻塞跑的（网络线程里），不能在里面 await 一次 DataStore，
 * 所以这里维护一份内存快照：[current] 给请求路径同步读，[publish] 由启动时的
 * Flow 收集与每次保存共同刷新。
 */
object AgentOutboundRepository {
    private val snapshot = MutableStateFlow(OutboundRewriteSettings())

    val stateFlow: StateFlow<OutboundRewriteSettings> = snapshot

    fun flow(): Flow<OutboundRewriteSettings> =
        SettingsDataStore.settingsFlow().map { it.outbound.normalized() }

    suspend fun settings(): OutboundRewriteSettings = SettingsDataStore.settings().outbound.normalized()

    suspend fun update(transform: (OutboundRewriteSettings) -> OutboundRewriteSettings) {
        SettingsDataStore.updateSettings { current ->
            current.copy(outbound = transform(current.outbound).normalized())
        }
    }

    /** 请求路径同步读取；未初始化时返回默认值（关闭）。 */
    fun current(): OutboundRewriteSettings = snapshot.value

    fun publish(settings: OutboundRewriteSettings) {
        snapshot.value = settings.normalized()
    }
}
