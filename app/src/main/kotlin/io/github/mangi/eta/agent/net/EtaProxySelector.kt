package io.github.mangi.eta.agent.net

import io.github.mangi.eta.data.model.NetworkProxySettings
import io.github.mangi.eta.data.model.NetworkProxyType
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * 进程级代理选择器。
 *
 * 通过 [publish] 更新配置即可立即生效——OkHttpClient 只需要在构造时挂上这个选择器，
 * 不必因为设置变更而重建客户端（客户端是 by lazy 的全局单例）。
 */
internal object EtaProxySelector : ProxySelector() {

    @Volatile
    private var current: NetworkProxySettings = NetworkProxySettings()

    @Volatile
    private var proxy: Proxy? = null

    fun publish(settings: NetworkProxySettings) {
        val normalized = settings.normalized()
        current = normalized
        proxy = if (normalized.usable) {
            Proxy(
                if (normalized.type == NetworkProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(normalized.host, normalized.port),
            )
        } else {
            null
        }
    }

    fun settings(): NetworkProxySettings = current

    override fun select(uri: URI?): List<Proxy> {
        val active = proxy ?: return NO_PROXY
        val host = uri?.host?.takeIf { it.isNotBlank() } ?: return listOf(active)
        return if (bypassed(host, current.bypass)) NO_PROXY else listOf(active)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    private fun bypassed(host: String, rules: String): Boolean {
        if (rules.isBlank()) return false
        val target = host.lowercase().trimEnd('.')
        return rules.split(',')
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .any { matches(target, it) }
    }

    private fun matches(host: String, rule: String): Boolean = when {
        rule.contains('/') -> ipv4InCidr(host, rule)
        rule.startsWith("*.") -> {
            val base = rule.removePrefix("*.")
            host == base || host.endsWith(".$base")
        }
        rule.startsWith(".") -> host.endsWith(rule)
        else -> host == rule || host.endsWith(".$rule")
    }

    /** 只处理 IPv4 CIDR；IPv6 网段直接不匹配，交给默认直连表。 */
    private fun ipv4InCidr(host: String, rule: String): Boolean {
        val parts = rule.split('/', limit = 2)
        if (parts.size != 2) return false
        val prefix = parts[1].toIntOrNull() ?: return false
        if (prefix !in 0..32) return false
        val network = ipv4(parts[0]) ?: return false
        val address = ipv4(host) ?: return false
        if (prefix == 0) return true
        val mask = (-1 shl (32 - prefix))
        return (network and mask) == (address and mask)
    }

    private fun ipv4(value: String): Int? {
        val octets = value.split('.')
        if (octets.size != 4) return null
        var result = 0
        for (octet in octets) {
            val part = octet.toIntOrNull() ?: return null
            if (part !in 0..255) return null
            result = (result shl 8) or part
        }
        return result
    }

    private val NO_PROXY = listOf(Proxy.NO_PROXY)
}

/** 给 OkHttpClient.Builder 挂上进程级代理（含代理认证）。 */
internal fun OkHttpClient.Builder.withEtaProxy(): OkHttpClient.Builder = apply {
    proxySelector(EtaProxySelector)
    proxyAuthenticator { _, response ->
        val settings = EtaProxySelector.settings()
        if (!settings.usable || settings.username.isBlank()) {
            null
        } else {
            response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(settings.username, settings.password))
                .build()
        }
    }
}
