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
    val virtualScreenFrameRate: Int = 60,
    val virtualScreenFloatWindowEnabled: Boolean = false,
    val virtualScreenFloatEdgeCollapseEnabled: Boolean = true,
    val virtualScreenFloatWindowWidthDp: Int = 0,
    val virtualScreenFloatWindowHeightDp: Int = 0,
    val virtualScreenFloatWindowAlpha: Float = 0.85f,
    val virtualScreenFloatWindowPosX: Int = 16,
    val virtualScreenFloatWindowPosY: Int = 120,
    val autoBackupEnabled: Boolean = false,
    val autoBackupIntervalHours: Int = 24,
    val autoBackupKeepCount: Int = 5,
    val autoBackupLastAt: Long = 0L,
    val autoBackupFingerprint: String = "",
    val autoBackupLastFileName: String = "",
    /** 自定义自动备份目录；空串表示默认的「外部存储/Eta备份」。 */
    val autoBackupDirectory: String = "",
    val appearance: AppearanceSettings = AppearanceSettings(),
)

internal object VirtualScreenIdleTimeout {
    val options = listOf(10, 20, 60, 0)
    fun normalize(minutes: Int): Int = minutes.takeIf { it in options } ?: 20
}

/** 自动备份周期（小时）。 */
internal object AutoBackupInterval {
    val options = listOf(24, 72, 168)
    fun normalize(hours: Int): Int = hours.takeIf { it in options } ?: 24
}

/** 自动备份保留份数，超出的删最旧。 */
internal object AutoBackupKeepCount {
    val options = listOf(3, 5, 10)
    fun normalize(count: Int): Int = count.takeIf { it in options } ?: 5
}

/** 虚拟屏出帧目标帧率；越低越省电，越高越跟手。 */
internal object VirtualScreenFrameRate {
    val options = listOf(60, 75, 90)
    fun normalize(value: Int): Int = value.takeIf { it in options } ?: 60
    /** Root 进程 JPEG 编码间隔（毫秒）。 */
    fun intervalMs(value: Int): Int = (1000 / normalize(value)).coerceIn(8, 66)
    /** 帧率越高，单帧 JPEG 质量略降，避免编码跟不上导致卡顿。 */
    fun jpegQuality(value: Int): Int = if (normalize(value) >= 75) 60 else 72
}
