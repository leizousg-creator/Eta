package io.github.mangi.eta.agent.terminal

/**
 * GitHub release 下载的代理前缀，按顺序尝试，全失败再回退官方直连。
 *
 * 国内直连 github.com 的 release 经常超时或中途断流，这里保留多个可选前缀：
 * 单个代理会挂，列表能顶住。新增前缀请放在末尾，避免打乱已验证的顺序。
 */
internal val GITHUB_PROXY_PREFIXES = listOf(
    "https://ghfast.top/",
    "https://ghproxy.net/",
    "https://gh-proxy.com/",
)
