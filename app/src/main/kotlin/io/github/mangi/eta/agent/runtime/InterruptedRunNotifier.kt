package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.EtaTrace

/**
 * 开机续跑提示。
 *
 * 开机时如果检查点里还有没跑完的会话，发一条通知告诉用户「上次有 N 个会话没跑完，点一下继续」；
 * 点开就是 Eta 主界面，走现成的会话恢复流程（不偷偷在后台自己开跑）。
 */
internal object InterruptedRunNotifier {

    private const val CHANNEL = "eta_interrupted_runs"
    private const val NOTIFICATION_ID = 4772

    fun notifyIfAny(context: Context) {
        val app = context.applicationContext
        val count = runCatching { AgentRunCheckpointStore.list(app).size }.getOrDefault(0)
        if (count <= 0) return
        runCatching {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "未完成的会话", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val builder = Notification.Builder(app, CHANNEL)
                .setSmallIcon(app.applicationInfo.icon)
                .setContentTitle("上次有 $count 个会话没跑完")
                .setContentText("点一下回 Eta 继续")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            launchIntent(app)?.let { builder.setContentIntent(it) }
            manager.notify(NOTIFICATION_ID, builder.build())
        }
        EtaTrace.log("boot.resume-notice", "开机提示未完成会话 $count 个")
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
