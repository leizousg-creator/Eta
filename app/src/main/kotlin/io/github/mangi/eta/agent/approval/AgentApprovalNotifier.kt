package io.github.mangi.eta.agent.approval

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R

/**
 * 审批通知：在用户没在看 Eta 的时候也能放行或拒绝。
 *
 * 动作走 [AgentApprovalReplyReceiver]，receiver 声明为非导出——
 * 通知里的 PendingIntent 由系统以本应用身份投递，外部应用伪造广播也到不了。
 */
internal object AgentApprovalNotifier {

    const val CHANNEL = "eta_agent_approval"
    const val ACTION_ALLOW = "io.github.mangi.eta.action.APPROVAL_ALLOW"
    const val ACTION_DENY = "io.github.mangi.eta.action.APPROVAL_DENY"
    const val EXTRA_REQUEST_ID = "approval_request_id"

    private const val NOTIFICATION_ID = 0x7A11

    fun show(context: Context, request: AgentApprovalBroker.Request) {
        val manager = runCatching { context.getSystemService(NotificationManager::class.java) }.getOrNull() ?: return
        ensureChannel(context, manager)
        val body = buildString {
            append(request.risk)
            append(" · ")
            append(request.toolName)
            if (request.argsPreview.isNotBlank()) {
                append('\n')
                append(request.argsPreview)
            }
        }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.approval_title))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(action(context, ACTION_DENY, R.string.approval_deny, request.id))
            .addAction(action(context, ACTION_ALLOW, R.string.approval_allow, request.id))
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun dismiss(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.approval_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    private fun action(
        context: Context,
        action: String,
        titleRes: Int,
        requestId: String,
    ): Notification.Action {
        val intent = Intent(context, AgentApprovalReplyReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_REQUEST_ID, requestId)
        val pending = PendingIntent.getBroadcast(
            context,
            (action.hashCode() * 31 + requestId.hashCode()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, context.getString(titleRes), pending).build()
    }
}
