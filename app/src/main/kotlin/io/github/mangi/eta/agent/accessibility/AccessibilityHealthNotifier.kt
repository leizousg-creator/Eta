package io.github.mangi.eta.agent.accessibility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.mangi.eta.R
import io.github.mangi.eta.core.EtaTrace

/**
 * 无障碍断连提醒。
 *
 * 点屏幕、读界面的能力全靠无障碍；断了以后平时只有等工具报错才知道。
 * 断连时发一条通知（写清原因与怎么修），重新连上就自动撤掉。
 *
 * 防误报：断连后先等 [VERIFY_DELAY_MS] 再确认一次，仍然没连上才发通知——
 * 覆盖安装新包时无障碍会短暂解绑，几秒内自己会连回来，不该打扰你；
 * 同一段时间也做冷却，避免反复抖动刷屏。
 */
internal object AccessibilityHealthNotifier {

    private const val CHANNEL = "eta_accessibility_health"
    private const val NOTIFICATION_ID = 4772
    private const val VERIFY_DELAY_MS = 8_000L
    private const val COOLDOWN_MS = 5 * 60_000L

    private val handler = Handler(Looper.getMainLooper())

    private var pending: Runnable? = null
    private var lastNotifiedAt = 0L

    fun onDisconnected(context: Context, reason: String) {
        val app = context.applicationContext
        pending?.let(handler::removeCallbacks)
        val task = Runnable {
            pending = null
            if (AgentAccessibilityService.isAvailable()) return@Runnable
            val now = System.currentTimeMillis()
            if (now - lastNotifiedAt < COOLDOWN_MS) return@Runnable
            lastNotifiedAt = now
            runCatching { show(app, reason) }
            EtaTrace.log("a11y", "断连提醒已发出：$reason")
        }
        pending = task
        handler.postDelayed(task, VERIFY_DELAY_MS)
    }

    fun onConnected(context: Context) {
        pending?.let(handler::removeCallbacks)
        pending = null
        val app = context.applicationContext
        runCatching {
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private fun show(app: Context, reason: String) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "无障碍状态", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.accessibility_lost_title))
                .setContentText(reason)
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(app.getString(R.string.accessibility_lost_body, reason)),
                )
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }
}
