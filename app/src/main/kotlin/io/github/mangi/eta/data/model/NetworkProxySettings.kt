package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder

/**
 * 出站网络代理配置。
 *
 * 只描述「一个代理端点」，不包含分流规则引擎：命中的请求整条走代理，[bypass] 里的主机直连。
 * 订阅链接（Clash / sing-box YAML）需要内嵌代理内核，不在这里处理；[parseLink] 只解析单节点链接。
 */
@Serializable
internal enum class NetworkProxyType {
    HTTP,
    SOCKS5,
}

@Serializable
internal data class NetworkProxySettings(
    val enabled: Boolean = false,
    val type: NetworkProxyType = NetworkProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val bypass: String = DEFAULT_BYPASS,
) {
    val usable: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    fun normalized(): NetworkProxySettings = copy(
        host = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("socks5://")
            .removePrefix("socks://")
            .trim()
            .trim('/'),
        port = if (port in 0..65535) port else 0,
        username = username.trim(),
        password = password.trim(),
        bypass = bypass.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")
            .ifBlank { DEFAULT_BYPASS },
    )

    companion object {
        const val DEFAULT_BYPASS = "localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"

        /**
         * 解析单节点代理链接：`http://user:pass@host:port`、`https://host:port`、
         * `socks5://host:port`，也接受裸 `host:port`。解析失败返回 null，调用方保留原配置。
         */
        fun parseLink(link: String, base: NetworkProxySettings = NetworkProxySettings()): NetworkProxySettings? {
            val raw = link.trim()
            if (raw.isEmpty()) return null
            val candidate = if (raw.contains("://")) raw else "http://$raw"
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port > 0) uri.port else defaultPort(uri.scheme)
            if (port !in 1..65535) return null
            val type = if (uri.scheme?.startsWith("socks", ignoreCase = true) == true) {
                NetworkProxyType.SOCKS5
            } else {
                NetworkProxyType.HTTP
            }
            val userInfo = uri.userInfo?.split(':', limit = 2).orEmpty()
            return base.copy(
                enabled = true,
                type = type,
                host = host,
                port = port,
                username = userInfo.getOrNull(0)?.let { decode(it) }.orEmpty(),
                password = userInfo.getOrNull(1)?.let { decode(it) }.orEmpty(),
            ).normalized()
        }

        private fun defaultPort(scheme: String?): Int = when (scheme?.lowercase()) {
            "socks5", "socks" -> 1080
            "https" -> 443
            else -> 8080
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
