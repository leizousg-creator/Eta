package io.github.mangi.eta.agent.model.provider

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** SSE 读取结果：正常读完，或被空闲看门狗掐断。 */
internal enum class SseReadOutcome { COMPLETED, TIMED_OUT }

/** 连续这么久没收到任何一行，就认为上游已经不再推进。 */
internal const val DEFAULT_SSE_IDLE_TIMEOUT_MS = 15_000L

private const val WATCHDOG_TICK_MS = 500L

/**
 * 只处理 SSE 分帧；各协议自行解释事件并在终态返回 false，不等待服务端关闭连接。
 *
 * 加了空闲看门狗：读流是阻塞的，上游既不结束也不发数据时（代理挂住、连接半死），
 * 单靠 readLine 会一直等到 socket 超时。这里每 [WATCHDOG_TICK_MS] 检查一次最近活动时间，
 * 超过 [idleTimeoutMs] 就关闭底层流把 readLine 打断，并返回 [SseReadOutcome.TIMED_OUT]：
 * 半截事件直接丢弃，由调用方决定是"按结束处理"还是"报错重试"。
 *
 * [dispatchPartialFrameAtEof] 保留上游语义：读到 EOF 时是否派发最后一帧未闭合的事件。
 */
internal fun readProviderSse(
    stream: InputStream,
    runController: AgentRunController,
    dispatchPartialFrameAtEof: Boolean = true,
    idleTimeoutMs: Long = DEFAULT_SSE_IDLE_TIMEOUT_MS,
    onEvent: (event: String, data: String) -> Boolean,
): SseReadOutcome {
    var event = ""
    val dataLines = mutableListOf<String>()
    val lastActivityAt = AtomicLong(System.currentTimeMillis())
    val timedOut = AtomicBoolean(false)

    fun dispatch(): Boolean {
        val name = event
        val payload = dataLines.joinToString("\n")
        event = ""
        dataLines.clear()
        if (payload.isBlank()) return true
        runController.throwIfCancelled()
        return onEvent(name, payload)
    }

    // 半死连接兜底：定时检查最近活动，超时就关流打断阻塞的 readLine。
    val watchdog = thread(name = "eta-sse-watchdog", isDaemon = true) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(WATCHDOG_TICK_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (System.currentTimeMillis() - lastActivityAt.get() >= idleTimeoutMs) {
                timedOut.set(true)
                runCatching { stream.close() }
                return@thread
            }
        }
    }

    var outcome = SseReadOutcome.COMPLETED
    try {
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                runController.throwIfCancelled()
                val line = try {
                    reader.readLine()
                } catch (failure: java.io.IOException) {
                    // 看门狗关流会打断阻塞的 readLine；这是预期的超时收尾，不当传输失败抛出。
                    if (timedOut.get()) null else throw failure
                }
                if (line == null) {
                    if (!timedOut.get() && dispatchPartialFrameAtEof) dispatch()
                    break
                }
                lastActivityAt.set(System.currentTimeMillis())
                when {
                    line.isEmpty() -> if (!dispatch()) break
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
                }
            }
        }
        if (timedOut.get()) outcome = SseReadOutcome.TIMED_OUT
    } finally {
        watchdog.interrupt()
    }
    return outcome
}
