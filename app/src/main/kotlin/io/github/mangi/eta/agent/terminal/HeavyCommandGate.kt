package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.runtime.AgentMemoryGate
import io.github.mangi.eta.core.EtaTrace
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 重任务（编译 / 构建 / 解包 / 长命令）互斥闸门。
 *
 * 多开会话同时跑编译是内存峰值的最大来源，光靠内存阈值来得太晚；这里保证：
 * 1. 重命令同一时间只跑一个；
 * 2. 内存紧张（剩余 <1200MB）时不让重命令开跑，避免在峰值上再叠一个峰值；
 * 3. 一开始等不到槽位就**当场**给出"已排队"提示，而不是拖到超时才报错；
 * 4. 槽位用信号量管理：正常结束、异常中断都在 finally 里释放；再加一层残留自检，
 *    持锁线程已死或持锁超过 10 分钟时强制释放，避免"锁残留"把后续任务全堵死。
 * 轻命令（ls、cat、grep 之类）完全不受影响。
 */
internal object HeavyCommandGate {

    /** 工具侧等待上限：模型发起的重命令可以排队等，但不无限等。 */
    const val TOOL_WAIT_MS = 120_000L

    /** 用户交互终端只等一小会：等太久会把界面卡住，不如直接告诉他在编译。 */
    const val CONSOLE_WAIT_MS = 5_000L

    private const val POLL_MS = 500L

    /** 持锁超过这个时长就视为残留（正常编译不会跑 10 分钟不释放）。 */
    private const val STALE_HOLD_MS = 10 * 60 * 1000L

    const val TIMEOUT_SLOT_MESSAGE =
        "已有重任务（编译／构建）在运行，本条未执行，等它结束后再试。"

    const val TIMEOUT_MEMORY_MESSAGE =
        "内存紧张，重任务（编译／构建）本轮未执行，等内存回落后再试。"

    private val heavyPatterns = listOf(
        "gradle", "assemble", "javac", "kotlinc", "apktool", "aapt", "aapt2", "d8 ", "r8 ",
        "cmake", "ninja", "make ", " make", "cargo", "npm ", "yarn ", "pnpm", "pip install",
        "python -m", "tar -x", "tar x", "unzip", "zip -r", "7z ", "dd ", "rsync", "git clone",
        "baksmali", "smali", "dex2jar", "jadx", "gcc ", "g++ ", "clang",
    )

    /** 用信号量而不是可重入锁：信号量允许任意线程释放，残留时才能被兜底回收。 */
    private val slot = Semaphore(1, true)

    @Volatile
    private var ownerThread: Thread? = null

    @Volatile
    private var acquiredAt = 0L

    fun isHeavy(command: String): Boolean {
        val text = command.lowercase()
        return heavyPatterns.any { pattern -> text.contains(pattern) }
    }

    /** 残留自检：持锁线程已死或持锁过久，就强制把槽位放回去。 */
    private fun releaseIfStale() {
        if (slot.availablePermits() > 0) return
        val owner = ownerThread
        val ownerGone = owner != null && !owner.isAlive
        val heldTooLong = acquiredAt > 0L && System.currentTimeMillis() - acquiredAt > STALE_HOLD_MS
        if (!ownerGone && !heldTooLong) return
        ownerThread = null
        acquiredAt = 0L
        slot.release()
        EtaTrace.log(
            "heavy.stale-release",
            "检测到重任务槽位残留（owner=${owner?.name.orEmpty()} 已死=$ownerGone 超时=$heldTooLong），已强制释放",
        )
    }

    fun <T> guarded(
        command: String,
        waitMs: Long = TOOL_WAIT_MS,
        onNotice: ((String) -> Unit)? = null,
        timeoutMessage: String = TIMEOUT_SLOT_MESSAGE,
        block: () -> T,
    ): T {
        if (!isHeavy(command)) return block()
        releaseIfStale()
        val deadline = System.currentTimeMillis() + waitMs
        var noticed = false
        var blockedByMemory = false
        while (true) {
            val tight = AgentMemoryGate.tier() >= AgentMemoryGate.TIER_QUEUE
            if (!tight && slot.tryAcquire(POLL_MS, TimeUnit.MILLISECONDS)) break
            blockedByMemory = tight
            if (!noticed) {
                noticed = true
                val notice = if (tight) {
                    "内存紧张（${AgentMemoryGate.describe()}），本条重任务已排队等待"
                } else {
                    "当前有编译在跑，本条已排队，结束后自动执行"
                }
                EtaTrace.log("heavy.queue", "$notice：${command.take(60)}")
                onNotice?.invoke(notice)
            }
            if (System.currentTimeMillis() >= deadline) {
                EtaTrace.log(
                    "heavy.reject",
                    "等待 ${waitMs / 1000}s 未获槽位（内存紧张=$blockedByMemory）：${command.take(60)}",
                )
                throw IllegalStateException(
                    if (blockedByMemory) TIMEOUT_MEMORY_MESSAGE else timeoutMessage,
                )
            }
            if (tight) Thread.sleep(POLL_MS)
        }
        ownerThread = Thread.currentThread()
        acquiredAt = System.currentTimeMillis()
        EtaTrace.log("heavy.start", command.take(80))
        return try {
            block()
        } finally {
            // 正常结束和异常中断都走这里；槽位绝不留给自己以外的下一次调用。
            ownerThread = null
            acquiredAt = 0L
            slot.release()
            EtaTrace.log("heavy.end", command.take(80))
        }
    }
}
