package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val memoryAutoCompactEnabled: Boolean = true,
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
    /** 自动备份周期（分钟）：支持 10 分钟这种短周期；0 表示沿用老的小时字段。 */
    val autoBackupIntervalMinutes: Int = 0,
    /** 老字段（小时），仅作历史设置/老备份的兼容回退。 */
    val autoBackupIntervalHours: Int = 24,
    val autoBackupKeepCount: Int = 5,
    val autoBackupLastAt: Long = 0L,
    val autoBackupFingerprint: String = "",
    val autoBackupLastFileName: String = "",
    /** 自定义自动备份目录；空串表示默认的「外部存储/Eta备份」。 */
    val autoBackupDirectory: String = "",
    /** 极致自动排查：本地定时巡检，只报异常，异常即留档。 */
    val diagPatrolEnabled: Boolean = false,

    /** 巡检周期（分钟）：5 ~ 43200，默认一天一次。 */
    val diagPatrolIntervalMinutes: Int = 24 * 60,

    /** 巡检报告保留份数：超出的删最旧，只动巡检自己的目录。 */
    val diagPatrolKeepCount: Int = 30,

    /** 只在出异常时提醒；关掉则每次巡检都提醒。 */
    val diagPatrolNotifyOnlyAbnormal: Boolean = true,

    val diagPatrolLastAt: Long = 0L,
    val diagPatrolLastFile: String = "",

    /** 上次巡检等级（正常/注意/异常），给界面和增量对比用。 */
    val diagPatrolLastLevel: String = "",
    val diagPatrolLastSummary: String = "",

    /** 打开 Eta 后悬浮胶囊常驻显示：没有任务时也留在屏幕上，点它看运行中会话。 */
    val overlayCapsulePinned: Boolean = false,

    /** 小胶囊上次停的位置（像素，收起态坐标系）；-1 表示还没拖过，按默认居中偏上。 */
    val overlayCapsulePosX: Int = -1,
    val overlayCapsulePosY: Int = -1,

    /** 最近打开过的会话 id（逗号分隔，最新在前）：重启后侧栏那一栏还在。 */
    val recentOpenedConversationIds: String = "",
    val appearance: AppearanceSettings = AppearanceSettings(),
)

internal object VirtualScreenIdleTimeout {
    val options = listOf(10, 20, 60, 0)
    fun normalize(minutes: Int): Int = minutes.takeIf { it in options } ?: 20
}

/** 自动备份周期：统一按分钟存，支持「每 10 分钟一次」这类短周期。 */
internal object AutoBackupInterval {

    /** 最短 5 分钟，再短会和闹钟重排互相打架。 */
    const val MIN_MINUTES = 5

    /** 最长 30 天。 */
    const val MAX_MINUTES = 30 * 24 * 60

    /** 默认 24 小时。 */
    const val DEFAULT_MINUTES = 24 * 60

    /** 周期不超过这个值时，每次都真的写一份备份（不按内容指纹跳过）。 */
    const val ALWAYS_WRITE_MAX_MINUTES = 60

    /** 常用预设（分钟）：10 分钟到 7 天。 */
    val presets = listOf(10, 30, 60, 180, 360, 720, 1440, 4320, 10080)

    /** 老设置里的小时预设，只用于兼容历史值。 */
    val legacyHourOptions = listOf(24, 72, 168)

    fun normalize(minutes: Int): Int = minutes.takeIf { it in MIN_MINUTES..MAX_MINUTES } ?: DEFAULT_MINUTES

    /** 把老的小时字段换算成分钟。 */
    fun fromLegacyHours(hours: Int): Int = normalize(hours * 60)

    /** 生效周期（分钟）：分钟字段优先，缺失或非法时回落到老的小时字段。 */
    fun effectiveMinutes(storedMinutes: Int, legacyHours: Int): Int =
        if (storedMinutes in MIN_MINUTES..MAX_MINUTES) storedMinutes else fromLegacyHours(legacyHours)

    /** 生效周期的毫秒数，供闹钟与到点判断使用。 */
    fun millis(storedMinutes: Int, legacyHours: Int): Long = effectiveMinutes(storedMinutes, legacyHours) * 60_000L
}

/** 自动备份保留份数，超出的删最旧；上限 60 份，够放 20~30 份这种短周期。 */
internal object AutoBackupKeepCount {

    const val MIN = 1
    const val MAX = 60

    /** 常用预设。 */
    val presets = listOf(3, 5, 10, 20, 30, 50)

    fun normalize(count: Int): Int = count.takeIf { it in MIN..MAX } ?: 5
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
