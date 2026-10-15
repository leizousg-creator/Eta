package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.core.EtaTrace

/**
 * 长任务里程碑汇报。
 *
 * 会话在跑的时候挂一条低优先级通知，每 30 秒刷新一句「已跑多久 · 当前在做什么」，
 * 编译、爬取这类要等几分钟的活儿不用盯着屏幕；任务结束（成功／失败／取消）立刻撤掉。
 * 只在还有会话在跑时存在，撤掉后再有新会话会重新挂上。
 */
internal object RunProgressNotifier {

    private const val CHANNEL = "eta_run_progress"
    private const val NOTIFICATION_ID = 4771
    private const val TICK_MS = 30_000L

    /** 兜底：这么久没有任何会话活动，就当这次运行已经结束，把通知撤掉。 */
    private const val IDLE_STOP_MS = 30 * 60_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    private var startedAt = 0L
    private var lastEventAt = 0L
    private var limitNote = ""
    private var step = "正在处理"
    private var active = false

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (System.currentTimeMillis() - lastEventAt > IDLE_STOP_MS) {
                // 通知一直在刷「已跑 N 分钟」，看着像还在干活；兜底撤掉，避免僵尸通知。
                stop(appContext ?: return, "超时兜底：30 分钟没有任何会话活动，自动撤销进度通知")
                return
            }
            post()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        startedAt = System.currentTimeMillis()
        lastEventAt = startedAt
        step = "正在处理"
        active = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        EtaTrace.log("run.progress.start", "开始汇报运行进度")
    }

    /** 并发上限被内存压低时，把原因直接写在通知里，不用去猜为什么只能同时跑一两个。 */
    fun noteSessionLimit(limit: Int, max: Int) {
        limitNote = if (limit < max) " · 内存紧张，并发上限 $limit/$max" else ""
    }

    /** 由事件流驱动，更新「当前在做什么」。 */
    fun note(event: AgentEvent) {
        if (!active) return
        lastEventAt = System.currentTimeMillis()
        when (event) {
            is AgentEvent.ToolStarted -> step = "正在执行：${event.name}"
            is AgentEvent.AssistantBlockDelta -> step = "正在输出回复"
            is AgentEvent.ContextCompaction -> step = "正在压缩上下文"
            is AgentEvent.ModelRetryScheduled -> step = "模型重试中"
            else -> Unit
        }
    }

    fun stop(context: Context, reason: String = "已撤销进度通知") {
        if (!active) return
        active = false
        limitNote = ""
        handler.removeCallbacks(tick)
        val app = (appContext ?: context.applicationContext)
        runCatching { manager(app).cancel(NOTIFICATION_ID) }
        EtaTrace.log("run.progress.stop", reason)
    }

    private fun post() {
        val app = appContext ?: return
        val minutes = ((System.currentTimeMillis() - startedAt) / 60_000L).coerceAtLeast(0L)
        val elapsed = if (minutes <= 0L) "刚跑起来" else "已跑 ${minutes} 分钟"
        runCatching {
            manager(app).notify(
                NOTIFICATION_ID,
                Notification.Builder(app, CHANNEL)
                    .setSmallIcon(app.applicationInfo.icon)
                    .setContentTitle("Eta 正在运行")
                    .setContentText("$elapsed · $step$limitNote")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(launchIntent(app))
                    .build(),
            )
        }
    }

    private fun manager(app: Context): NotificationManager {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "运行进度", NotificationManager.IMPORTANCE_LOW),
        )
        return manager
    }

    private fun launchIntent(app: Context): PendingIntent? {
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return null
        return PendingIntent.getActivity(
            app,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
