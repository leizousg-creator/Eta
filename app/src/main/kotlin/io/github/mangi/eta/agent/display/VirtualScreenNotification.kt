package io.github.mangi.eta.agent.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

internal object VirtualScreenNotification {
    private const val CHANNEL = "eta_virtual_screen"
    private const val ID = 1110

    fun show(context: Context, sessionId: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, context.getString(R.string.virtual_screen_notification_channel), NotificationManager.IMPORTANCE_LOW,
            ))
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, VirtualScreenViewerActivity::class.java)
                    .setData(Uri.parse("eta-virtual-screen://view/$sessionId"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(sessionId, ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.virtual_screen_notification_title))
                .setContentText(context.getString(R.string.virtual_screen_notification_content))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build())
        }.onFailure { AndroidAgentLogger.warn("Virtual screen notification failed: type=${it.safeLogType()}") }
    }

    fun cancel(context: Context, sessionId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(sessionId, ID)
    }

    private const val COMPLETION_CHANNEL = "eta_task_completion"
    private const val COMPLETION_ID = 1111

    /** 虚拟屏指令运行完毕后，走一条高优先级通知通道，由 ColorOS 尽量归入流体云展示。 */
    fun showCompletion(context: Context, ok: Boolean, detail: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                COMPLETION_CHANNEL,
                context.getString(R.string.virtual_screen_completion_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ))
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(COMPLETION_ID, Notification.Builder(context, COMPLETION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (ok) context.getString(R.string.virtual_screen_completion_ok_title)
                    else context.getString(R.string.virtual_screen_completion_fail_title))
                .setContentText(detail.ifBlank { context.getString(R.string.virtual_screen_completion_ok_body) })
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(open).build())
        }.onFailure { AndroidAgentLogger.warn("Virtual screen completion notification failed: type=${it.safeLogType()}") }
    }
}
