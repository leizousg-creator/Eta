package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 虚拟屏（灵屏）的本地开关。
 *
 * 目前只包含已经接上实现的两项：空闲回收。帧率、熄屏执行、浮窗、
 * 主屏回退等字段要等对应实现落地再加，避免出现「开关摆着但不生效」。
 */
@Serializable
data class VirtualScreenSettings(
    val idleReclaimEnabled: Boolean = true,
    val idleTimeoutMinutes: Int = DEFAULT_IDLE_MINUTES,
    /** 目标刷新率：建屏时作为 VirtualDisplayConfig 的建议刷新率。 */
    val frameRate: Int = DEFAULT_FRAME_RATE,
) {
    fun normalized(): VirtualScreenSettings = copy(
        idleTimeoutMinutes = idleTimeoutMinutes.coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES),
        frameRate = frameRate.coerceIn(MIN_FRAME_RATE, MAX_FRAME_RATE),
    )

    /** 给调度器用的毫秒值；关闭回收时返回 0。 */
    val idleTimeoutMs: Long
        get() = if (idleReclaimEnabled) idleTimeoutMinutes * 60_000L else 0L

    companion object {
        const val MIN_IDLE_MINUTES = 1
        const val MAX_IDLE_MINUTES = 120
        const val DEFAULT_IDLE_MINUTES = 20
        const val MIN_FRAME_RATE = 60
        const val MAX_FRAME_RATE = 165
        const val DEFAULT_FRAME_RATE = 60
    }
}
