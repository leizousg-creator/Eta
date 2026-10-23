package io.github.mangi.eta.hook.vivo

/** The first adapter is validated against OriginOS 6's BlueLM Copilot 6.8.5.3. */
internal object VivoTakeoverPolicy {
    const val SUPPORTED_VERSION_CODE = 68503L
    const val MAX_PROMPT_CHARS = 32_000

    fun isSupportedVersion(versionCode: Long): Boolean = versionCode == SUPPORTED_VERSION_CODE

    /**
     * 岛（流体云）显示用的宽松判断：>= 基线版本即可尝试。
     *
     * 与 [isSupportedVersion] 分开是刻意的——hook 安装仍要求精确版本（内部 API 一变就崩），
     * 而岛只是 bindService 通信，协议不兼容时会 bind 失败并优雅降级，不会拖垮小V。
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
