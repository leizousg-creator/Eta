package io.github.mangi.eta.hook.vivo

/** The first adapter is validated against OriginOS 6's BlueLM Copilot 6.8.5.3. */
internal object VivoTakeoverPolicy {
    const val SUPPORTED_VERSION_CODE = 68503L
    const val MAX_PROMPT_CHARS = 32_000

    /**
     * hook 安装判断。
     *
     * 原实现要求与基线**精确相等**，等于把适配范围钉死在一台设备上（6.8.5.3 → 68503）。
     * 但 vivo 已换过编码：6.10.0.1 是「主*1_000_000 + 次*10_000 + 补丁*100 + 构建」= 6100001，
     * 与旧编码不可直接比大小，所以不再做数值比较，只拒绝读不到版本的情况（-1）。
     * 内部 API 真变了也只是 hook 抛异常，由 LSPosed 的 exceptionMode=protective 隔离，
     * 与 [isIslandCompatible] 的宽松口径保持一致。
     */
    fun isSupportedVersion(versionCode: Long): Boolean = versionCode > 0

    /**
     * 岛（流体云）显示用的宽松判断：>= 基线版本即可尝试。
     *
     * 与 [isSupportedVersion] 分开是刻意的——hook 安装要过内部 API，岛只是 bindService 通信，
     * 协议不兼容时会 bind 失败并优雅降级，不会拖垮小V。
     */
    fun isIslandCompatible(versionCode: Long): Boolean = versionCode >= SUPPORTED_VERSION_CODE

    fun isSupportedInputMode(inputMode: String?, fullDuplex: Boolean): Boolean =
        !fullDuplex && inputMode in setOf("inputmode_keyboard", "voice", "longpress_voice")

    fun prompt(
        text: String,
        enabled: Boolean,
        requirePrefix: Boolean,
        hasAttachments: Boolean = false,
    ): String? {
        if (!enabled || hasAttachments) return null
        val input = text.trim()
        if (input.isBlank() || input.length > MAX_PROMPT_CHARS) return null
        val prefixed = when {
            input.startsWith("/agent%20") -> input.removePrefix("/agent%20").trim()
            input.startsWith("/agent") && input.getOrNull(6)?.isWhitespace() == true ->
                input.substring(6).trim()
            input == "/agent" -> ""
            else -> null
        }
        return (prefixed ?: input.takeUnless { requirePrefix })?.takeIf { it.isNotBlank() }
    }
}
