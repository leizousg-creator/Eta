package io.github.mangi.eta.agent.approval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知栏上的「允许 / 拒绝」。
 *
 * 只处理 [AgentApprovalNotifier] 发出的两种动作；请求 id 已失效时
 * [AgentApprovalBroker.answer] 返回 false，直接忽略即可（例如用户点得比超时更晚）。
 */
internal class AgentApprovalReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != AgentApprovalNotifier.ACTION_ALLOW && action != AgentApprovalNotifier.ACTION_DENY) return
        val requestId = intent.getStringExtra(AgentApprovalNotifier.EXTRA_REQUEST_ID) ?: return
        AgentApprovalBroker.answer(requestId, action == AgentApprovalNotifier.ACTION_ALLOW)
        AgentApprovalNotifier.dismiss(context)
    }
}
