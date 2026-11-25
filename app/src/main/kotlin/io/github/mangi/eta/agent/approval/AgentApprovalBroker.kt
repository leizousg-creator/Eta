package io.github.mangi.eta.agent.approval

import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 审批的请求/应答中枢。
 *
 * 工具执行线程在这里阻塞等待；UI、通知或浮窗通过 [answer] 放行或拒绝。
 * 没有应答就超时，按 [Answer.NOT_ANSWERED] 处理，工具不执行——
 * 对应上游语义 APPROVAL_NOT_ANSWERED。
 *
 * 线程模型：`awaitDecision` 跑在工具执行线程（后台），可以安全阻塞；
 * [pending] 是给 UI 观察的最新一条待决请求。
 */
internal object AgentApprovalBroker {

    enum class Answer {
        APPROVED,
        DENIED,
        NOT_ANSWERED,

        /** 运行被停止或取消，请求作废。 */
        CANCELLED,
        ;

        val allowed: Boolean
            get() = this == APPROVED
    }

    data class Request(
        val id: String,
        val runId: String,
        val toolName: String,
        val argsPreview: String,
        val risk: String,
    )

    private class Slot(val request: Request) {
        val done = AtomicBoolean(false)
        @Volatile
        var answer: Answer = Answer.NOT_ANSWERED
        val latch = java.util.concurrent.CountDownLatch(1)

        fun complete(next: Answer): Boolean {
            if (!done.compareAndSet(false, true)) return false
            answer = next
            latch.countDown()
            return true
        }
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val pendingFlow = MutableStateFlow<Request?>(null)

    @Volatile
    private var appContext: android.content.Context? = null

    /** 由 Application 注入；单测不注入，通知相关调用自动跳过。 */
    fun initialize(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /** 最新待决请求；UI 用它决定是否显示审批条。 */
    val pending: StateFlow<Request?> = pendingFlow

    /**
     * 发出一次审批请求并阻塞等待结果。
     *
     * @param timeoutMillis 等待上限，取设置里的 waitMillis。
     * @param isCancelled 每次唤醒后检查；运行已取消时立即作废，不等满超时。
     */
    fun awaitDecision(
        runId: String,
        toolName: String,
        argsPreview: String,
        risk: String,
        timeoutMillis: Long,
        isCancelled: () -> Boolean = { false },
    ): Answer {
        if (timeoutMillis <= 0L) return Answer.NOT_ANSWERED
        val request = Request(
            id = UUID.randomUUID().toString().take(8),
            runId = runId,
            toolName = toolName,
            argsPreview = argsPreview.take(MAX_PREVIEW_CHARS),
            risk = risk,
        )
        val slot = Slot(request)
        slots[request.id] = slot
        pendingFlow.value = request
        appContext?.let { AgentApprovalNotifier.show(it, request) }
        log {
            "Agent approval requested: id=${request.id}, run=$runId, tool=$toolName, risk=$risk"
        }
        try {
            val deadline = System.currentTimeMillis() + timeoutMillis
            var answered = false
            while (System.currentTimeMillis() < deadline) {
                if (slot.latch.await(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    answered = true
                    break
                }
                if (isCancelled()) {
                    slot.complete(Answer.CANCELLED)
                    answered = true
                    break
                }
                if (slot.done.get()) {
                    answered = true
                    break
                }
            }
            if (!answered) slot.complete(Answer.NOT_ANSWERED)
            val result = slot.answer
            log { "Agent approval answered: id=${request.id}, answer=${result.name}, tool=$toolName" }
            return result
        } catch (interrupted: InterruptedException) {
            slot.complete(Answer.CANCELLED)
            Thread.currentThread().interrupt()
            return Answer.CANCELLED
        } finally {
            slots.remove(request.id)
            appContext?.let { AgentApprovalNotifier.dismiss(it) }
            publishNextPending()
            // 还有别的 run 在等：把新的一条顶上，不然通知栏会空着。
            pendingFlow.value?.let { next -> appContext?.let { AgentApprovalNotifier.show(it, next) } }
        }
    }

    /** 用户点了允许或拒绝。返回 false 表示该请求已失效。 */
    fun answer(id: String, allow: Boolean): Boolean {
        val slot = slots[id] ?: return false
        val applied = slot.complete(if (allow) Answer.APPROVED else Answer.DENIED)
        if (applied) publishNextPending()
        return applied
    }

    /** 运行被停止：作废它名下所有待决请求，避免线程等满超时。 */
    fun cancelRun(runId: String) {
        var changed = false
        slots.values.filter { it.request.runId == runId }.forEach { slot ->
            if (slot.complete(Answer.CANCELLED)) changed = true
        }
        if (changed) publishNextPending()
    }

    fun cancelAll() {
        slots.values.forEach { it.complete(Answer.CANCELLED) }
        pendingFlow.value = null
    }

    private fun publishNextPending() {
        val next = slots.values
            .map { it.request }
            .minByOrNull { it.id }
        pendingFlow.value = next
    }

    private const val POLL_INTERVAL_MS = 200L
    private const val MAX_PREVIEW_CHARS = 600

    /**
     * 诊断日志不能影响审批语义：单测 JVM 里 Log 未打桩会抛异常，
     * 在工具线程里把异常抛出去会让「该拒绝的调用」变成崩溃。
     */
    private inline fun log(message: () -> String) {
        runCatching { AndroidAgentLogger.info(message()) }
    }

    /** 供测试重置。 */
    fun resetForTest() {
        slots.clear()
        pendingFlow.value = null
    }
}
