package io.github.mangi.eta.agent.display

import android.os.SystemClock

/** 胶囊「暂停」用的帧闸门：暂停时画面定格，会话与任务都保留。 */
internal object VirtualScreenFrameGate {
    @Volatile
    var paused: Boolean = false

    /** 最近一次向虚拟屏下发输入的时间：触屏后短时全速追帧，别让操作等下一拍。 */
    @Volatile
    var lastInputAt: Long = 0L

    /** 查看页是否在前台：此时小窗降到半速出帧，避免两份整帧解码互抢 CPU。 */
    @Volatile
    var viewerForeground: Boolean = false

    fun markInput() {
        lastInputAt = SystemClock.elapsedRealtime()
    }

    fun sinceInputMs(): Long = SystemClock.elapsedRealtime() - lastInputAt
}
