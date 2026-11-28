package io.github.mangi.eta.agent.island

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 从 Eta 主进程把任务状态推给原子岛。
 *
 * 为什么不能直接在通知里写 superx 字段：实测第三方应用的 `notification.superx.island`
 * 与 `capsule` 子 Bundle 在 NMS 侧就被剥掉（只留 `displays` / `islandNotify` 和一个重建
 * 出来的 `SUPERX_VALUE`），插件读到 `capsule.state = 0` 就按「删除岛」处理。
 *
 * 岛的内部 API 只存在于 `com.vivo.systemuiplugin:island` 进程，
 * 那边由 [io.github.mangi.eta.hook.vivo.island.VivoIslandBridge] 接收，所以这里只能走广播。
 *
 * 去重是必要的：`AgentExecutionService` 每 6 秒有一次心跳重发通知，
 * 状态没变就不该反复触发岛的渲染。
 */
internal object VivoIslandPublisher {

    private const val TARGET_PACKAGE = "com.vivo.systemuiplugin"
    private const val ACTION_SHOW = "io.github.mangi.eta.island.SHOW"
    private const val ACTION_REMOVE = "io.github.mangi.eta.island.REMOVE"
    private const val EXTRA_ID = "id"
    private const val EXTRA_CONTENT = "content"
    private const val EXTRA_CLASS = "className"
    private const val EXTRA_SHOW_SECONDS = "showSeconds"

    private const val ISLAND_ID = "eta_agent_task"
    private const val MAIN_ACTIVITY = "io.github.mangi.eta.ui.MainActivity"

    @Volatile
    private var lastContent: String? = null

    @Volatile
    private var lastPublishedAt = 0L

    /** 岛只显示 showTime 秒，心跳要按间隔重投递才能续期；同内容也别刷太勤。 */
    private const val RESEND_AFTER_MS = 5_000L

    fun publish(context: Context, content: String, showSeconds: Int) {
        if (content.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (content == lastContent && now - lastPublishedAt < RESEND_AFTER_MS) return
        lastContent = content
        lastPublishedAt = now
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_SHOW)
                    .setPackage(TARGET_PACKAGE)
                    .putExtra(EXTRA_ID, ISLAND_ID)
                    .putExtra(EXTRA_CONTENT, content.take(40))
                    .putExtra(EXTRA_CLASS, MAIN_ACTIVITY)
                    .putExtra(EXTRA_SHOW_SECONDS, showSeconds),
            )
        }
    }

    fun hide(context: Context) {
        if (lastContent == null) return
        lastContent = null
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_REMOVE).setPackage(TARGET_PACKAGE).putExtra(EXTRA_ID, ISLAND_ID),
            )
        }
    }
}
