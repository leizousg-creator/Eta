package io.github.mangi.eta.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AgentRetrySettings
import io.github.mangi.eta.data.model.NetworkProxySettings
import io.github.mangi.eta.data.model.NetworkProxyType
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 网络与重试设置。
 *
 * 代理只描述一个端点：命中的请求整条走它，[NetworkProxySettings.bypass] 里的主机直连。
 * 订阅链接（Clash / sing-box）需要代理内核，这里不做；「从链接导入」只解析单节点链接。
 */
@Composable
internal fun NetworkProxyScreen(
    proxy: NetworkProxySettings,
    retry: AgentRetrySettings,
    onBack: () -> Unit,
    onUpdateProxy: ((NetworkProxySettings) -> NetworkProxySettings) -> Unit,
    onUpdateRetry: ((AgentRetrySettings) -> AgentRetrySettings) -> Unit,
    onImportLink: (String) -> Boolean,
) {
    var host by remember(proxy.host) { mutableStateOf(proxy.host) }
    var port by remember(proxy.port) { mutableStateOf(if (proxy.port > 0) proxy.port.toString() else "") }
    var username by remember(proxy.username) { mutableStateOf(proxy.username) }
    var password by remember(proxy.password) { mutableStateOf(proxy.password) }
    var bypass by remember(proxy.bypass) { mutableStateOf(proxy.bypass) }
    var link by remember { mutableStateOf("") }
    var linkNotice by remember { mutableStateOf("") }

    MiuixScaffold(title = "网络与重试", onBack = onBack) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
                    .navigationBarsPadding()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(start = sidePadding, end = sidePadding),
                overscrollEffect = null,
            ) {
                item(key = "proxy-title") { EtaPreferenceGroupTitle("代理") }
                item(key = "proxy-group") {
                    EtaPreferenceGroup(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    ) {
                        EtaSwitchPreference(
                            title = "启用代理",
                            summary = "模型请求、网页搜索、MCP、模型列表、更新检查都走这里；关掉即直连",
                            checked = proxy.enabled,
                            onCheckedChange = { enabled -> onUpdateProxy { it.copy(enabled = enabled) } },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = "类型",
                            summary = if (proxy.type == NetworkProxyType.HTTP) {
                                "HTTP：经 CONNECT 隧道转发，Clash 的 7890 这类端口就是它"
                            } else {
                                "SOCKS5：TCP 级转发，本地 socks 端口用这个"
                            },
                            endActions = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    EtaChoiceChip(
                                        text = "HTTP",
                                        selected = proxy.type == NetworkProxyType.HTTP,
                                        onClick = { onUpdateProxy { it.copy(type = NetworkProxyType.HTTP) } },
                                    )
                                    EtaChoiceChip(
                                        text = "SOCKS5",
                                        selected = proxy.type == NetworkProxyType.SOCKS5,
                                        onClick = { onUpdateProxy { it.copy(type = NetworkProxyType.SOCKS5) } },
                                    )
                                }
                            },
                        )
                    }
                }

                item(key = "endpoint-title") { EtaPreferenceGroupTitle("代理地址") }
                item(key = "endpoint-group") {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        TextField(
                            value = host,
                            onValueChange = { value ->
                                host = value
                                onUpdateProxy { it.copy(host = value) }
                            },
                            label = "服务器地址，例如 127.0.0.1",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = port,
                            onValueChange = { value ->
                                val digits = value.filter { it.isDigit() }.take(5)
                                port = digits
                                onUpdateProxy { it.copy(port = digits.toIntOrNull() ?: 0) }
                            },
                            label = "端口，例如 3066",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = username,
                            onValueChange = { value ->
                                username = value
                                onUpdateProxy { it.copy(username = value) }
                            },
                            label = "用户名（可选）",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = password,
                            onValueChange = { value ->
                                password = value
                                onUpdateProxy { it.copy(password = value) }
                            },
                            label = "密码（可选）",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = bypass,
                            onValueChange = { value ->
                                bypass = value
                                onUpdateProxy { it.copy(bypass = value) }
                            },
                            label = "不走代理的地址，逗号分隔",
                            useLabelAsPlaceholder = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item(key = "link-title") { EtaPreferenceGroupTitle("从链接导入") }
                item(key = "link-group") {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        TextField(
                            value = link,
                            onValueChange = {
                                link = it
                                linkNotice = ""
                            },
                            label = "http://user:pass@host:port 或 socks5://host:port",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        EtaTextButton(
                            text = "解析并填入",
                            enabled = link.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (onImportLink(link)) {
                                    link = ""
                                    linkNotice = "已填入上面的地址"
                                } else {
                                    linkNotice = "链接无法识别，请检查格式"
                                }
                            },
                        )
                        if (linkNotice.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            EtaPreference(title = linkNotice, summary = "订阅链接需要代理内核，这里只解析单个节点")
                        }
                    }
                }

                item(key = "retry-title") { EtaPreferenceGroupTitle("重试") }
                item(key = "retry-group") {
                    EtaPreferenceGroup(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    ) {
                        EtaPreference(
                            title = "最多重试 ${retry.maxRetries} 次",
                            summary = "只对可重试的失败生效（超时、断连、限流、5xx）；认证、额度、参数错误不重试",
                            endActions = {
                                EtaTextButton(
                                    text = "−",
                                    enabled = retry.maxRetries > AgentRetrySettings.MIN_RETRIES,
                                    onClick = {
                                        onUpdateRetry { it.copy(maxRetries = it.maxRetries - 1) }
                                    },
                                )
                                EtaTextButton(
                                    text = "+",
                                    enabled = retry.maxRetries < AgentRetrySettings.MAX_RETRIES,
                                    onClick = {
                                        onUpdateRetry { it.copy(maxRetries = it.maxRetries + 1) }
                                    },
                                )
                            },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = "首次等待 ${retry.baseDelayMs / 1000.0} 秒起",
                            summary = "之后每次翻倍：2 秒 → 4 秒 → 8 秒",
                            endActions = {
                                EtaTextButton(
                                    text = "−",
                                    enabled = retry.baseDelayMs > AgentRetrySettings.MIN_DELAY_MS,
                                    onClick = {
                                        onUpdateRetry {
                                            it.copy(baseDelayMs = it.baseDelayMs - 500)
                                        }
                                    },
                                )
                                EtaTextButton(
                                    text = "+",
                                    enabled = retry.baseDelayMs < AgentRetrySettings.MAX_DELAY_MS,
                                    onClick = {
                                        onUpdateRetry {
                                            it.copy(baseDelayMs = it.baseDelayMs + 500)
                                        }
                                    },
                                )
                            },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = "失败原因会写在重试气泡里",
                            summary = "例如「DNS 解析失败」「SSL 握手失败」「连接被拒绝」，便于判断是网络、代理还是服务商问题",
                        )
                    }
                }
            }
        }
    }
}
