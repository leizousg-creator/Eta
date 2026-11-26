package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.AgentRetrySettings

/**
 * 重试预算的进程内快照。
 *
 * Agent 循环跑在网络线程里，不能在建连途中 await DataStore，所以这里放一份
 * volatile 快照：启动时由设置流发布一次，保存设置后再发布一次。
 */
internal object AgentRetryPolicy {

    @Volatile
    private var current: AgentRetrySettings = AgentRetrySettings()

    fun publish(settings: AgentRetrySettings) {
        current = settings.normalized()
    }

    fun settings(): AgentRetrySettings = current
}
