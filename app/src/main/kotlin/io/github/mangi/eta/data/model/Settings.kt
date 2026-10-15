package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val autoMemoryEnabled: Boolean = true,
    val autoSkillsEnabled: Boolean = true,
    val virtualScreenEnabled: Boolean = false,
    val virtualScreenOffEnabled: Boolean = false,
    val virtualScreenFallbackEnabled: Boolean = false,
    val virtualScreenAutoRestartApps: Boolean = false,
    val virtualScreenIdleTimeoutMinutes: Int = 20,
    val virtualScreenFloatWindowEnabled: Boolean = false,
    val virtualScreenFloatEdgeCollapseEnabled: Boolean = true,
    val virtualScreenFloatWindowWidthDp: Int = 0,
    val virtualScreenFloatWindowHeightDp: Int = 0,
    val virtualScreenFloatWindowAlpha: Float = 0.85f,
    val virtualScreenFloatWindowPosX: Int = 16,
    val virtualScreenFloatWindowPosY: Int = 120,
    val appearance: AppearanceSettings = AppearanceSettings(),
)

internal object VirtualScreenIdleTimeout {
    val options = listOf(10, 20, 60, 0)
    fun normalize(minutes: Int): Int = minutes.takeIf { it in options } ?: 20
}
