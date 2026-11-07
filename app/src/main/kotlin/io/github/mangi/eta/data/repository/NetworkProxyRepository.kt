package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.net.EtaProxySelector
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.NetworkProxySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 网络代理设置。
 *
 * 与 [AgentOutboundRepository] 同样的两段式：DataStore 是真源，内存里另存一份快照，
 * 因为 OkHttp 在建连时是同步读代理的，不能 await。
 * [publish] 由启动时的 Flow 收集与每次保存共同刷新，[EtaProxySelector] 据此立即生效。
 */
internal object NetworkProxyRepository {

    private val snapshot = MutableStateFlow(NetworkProxySettings())

    val stateFlow: StateFlow<NetworkProxySettings> = snapshot

    fun flow(): Flow<NetworkProxySettings> = SettingsDataStore.networkProxyFlow()

    suspend fun settings(): NetworkProxySettings = SettingsDataStore.networkProxy()

    suspend fun update(transform: (NetworkProxySettings) -> NetworkProxySettings) {
        val next = transform(snapshot.value).normalized()
        SettingsDataStore.setNetworkProxy(next)
        publish(next)
    }

    fun publish(settings: NetworkProxySettings) {
        val normalized = settings.normalized()
        snapshot.value = normalized
        EtaProxySelector.publish(normalized)
    }

    /** 从代理链接解析；失败返回 null，界面据此提示「链接无法识别」。 */
    fun parseLink(link: String, base: NetworkProxySettings): NetworkProxySettings? =
        NetworkProxySettings.parseLink(link, base)
}
