package io.github.mangi.eta.agent.runtime

/** 用户任务的运行引用；通知停止只消费这里登记的任务，不接管 Root daemon。 */
internal class ExecutionLeaseRegistry {
    private data class Lease(val owner: Long?, val allowBoundFallback: Boolean, val onStop: () -> Unit)
    private val leases = linkedMapOf<String, Lease>()
    private var activeOwner: Long? = null

    @Synchronized fun acquire(id: String, allowBoundFallback: Boolean = false, onStop: () -> Unit): Boolean {
        require(id.isNotBlank())
        if (id in leases) return false
        leases[id] = Lease(activeOwner, allowBoundFallback, onStop)
        return true
    }

    @Synchronized fun release(id: String) { leases.remove(id) }
    @Synchronized fun count(): Int = leases.size

    /**
     * 回收孤儿 run 租约。
     *
     * `run:<id>` 只在 `AgentRuntimeService.beforeTerminalPublish` 里释放；
     * 一旦 run 被取消、抛异常、或进程内协议提前关闭而没有走到终态发布，
     * 这条 lease 就再也没人释放，前台服务与通知会一直挂着
     * （日志表现为 `Execution service kept alive with idle run: leases=N`）。
     *
     * 只有在调用方确认「没有任何活跃 agent run」时才可以调用本方法——
     * 那时残留的 `run:*` 必然是孤儿。
     */
    @Synchronized fun releaseRunLeases(): List<String> {
        val orphans = leases.keys.filter { it.startsWith(RUN_LEASE_PREFIX) }
        orphans.forEach(leases::remove)
        return orphans
    }

    @Synchronized fun describe(): String =
        leases.keys.joinToString(",").ifEmpty { "（空）" }

    private companion object {
        const val RUN_LEASE_PREFIX = "run:"
    }

    @Synchronized fun attachOwner(owner: Long) {
        activeOwner = owner
        leases.replaceAll { _, lease -> if (lease.owner == null) lease.copy(owner = owner) else lease }
    }

    @Synchronized fun closeOwnerIfIdle(owner: Long): Boolean {
        if (leases.isNotEmpty()) return false
        if (activeOwner == owner) activeOwner = null
        return true
    }

    /** 旧服务销毁时，不能取消在其 stopSelf 之后为下一次启动登记的任务。 */
    @Synchronized fun drainOwner(owner: Long): List<() -> Unit> {
        if (activeOwner == owner) activeOwner = null
        val owned = leases.filterValues { it.owner == owner }
        owned.keys.forEach(leases::remove)
        return owned.values.map { it.onStop }
    }

    @Synchronized fun drain(startFailed: Boolean = false): List<() -> Unit> = leases.values
        .filterNot { startFailed && it.allowBoundFallback }
        .map { it.onStop }
        .also { leases.clear() }
}
