package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.OutboundRewriteSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    fun flow(): Flow<OutboundRewriteSettings> = SettingsDataStore.outboundRewriteFlow()

    suspend fun settings(): OutboundRewriteSettings = SettingsDataStore.outboundRewrite()

    suspend fun update(transform: (OutboundRewriteSettings) -> OutboundRewriteSettings) {
        val current = SettingsDataStore.outboundRewrite()
        SettingsDataStore.setOutboundRewrite(transform(current))
    }

    /** 请求路径同步读取；未初始化时返回默认值（关闭）。 */
    fun current(): OutboundRewriteSettings = snapshot.value

    fun publish(settings: OutboundRewriteSettings) {
        snapshot.value = settings.normalized()
    }
}
