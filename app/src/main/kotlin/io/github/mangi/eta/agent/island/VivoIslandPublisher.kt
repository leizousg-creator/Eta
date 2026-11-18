package io.github.mangi.eta.agent.island

import android.content.Context
import android.content.Intent

/**
 * 从 Eta 主进程把任务状态推给原子岛。
 *
 * 岛的内部 API 只存在于 `com.vivo.systemuiplugin:island` 进程，
 * 那边由 [io.github.mangi.eta.hook.vivo.island.VivoIslandBridge] 接收，
 * 所以这里只能走广播。
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

    private const val ISLAND_ID = "eta_agent_task"
    private const val MAIN_ACTIVITY = "io.github.mangi.eta.ui.MainActivity"

    @Volatile
    private var lastContent: String? = null

    fun publish(context: Context, content: String) {
        if (content.isBlank() || content == lastContent) return
        lastContent = content
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_SHOW)
                    .setPackage(TARGET_PACKAGE)
                    .putExtra(EXTRA_ID, ISLAND_ID)
                    .putExtra(EXTRA_CONTENT, content.take(40))
                    .putExtra(EXTRA_CLASS, MAIN_ACTIVITY)
            )
        }
    }

    fun hide(context: Context) {
        if (lastContent == null) return
        lastContent = null
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_REMOVE).setPackage(TARGET_PACKAGE).putExtra(EXTRA_ID, ISLAND_ID)
            )
        }
    }
}
