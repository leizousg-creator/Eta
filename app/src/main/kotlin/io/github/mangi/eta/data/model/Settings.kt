package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val appearance: AppearanceSettings = AppearanceSettings(),
    /** ETA-FORK: 出站改写（发送前按规则替换正文里的词）。 */
    val outbound: OutboundRewriteSettings = OutboundRewriteSettings(),
)
