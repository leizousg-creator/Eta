package io.github.mangi.eta.agent.runtime

import android.content.Context
import java.io.File

/**
 * 多开会话的内存闸门（只看剩余内存绝对值）＋ 弹性会话上限。
 *
 * 不用占用百分比：Android 把可回收缓存也算进可用内存，百分比既虚又抖，小内存机上还可能比绝对值
 * 触发得更晚，留着只会让人误以为它在起作用。
 *
 * 闸门分工（共用同一套内存读数，不新增独立判据）：
 * - 剩余 <1200MB：新会话、新任务进排队队列；回落到阈值以上就放行；运行中的继续跑完
 * - 剩余 <800MB：队列停止放行，不再接收新任务入队，已在执行的继续跑完
 * - 剩余 <500MB：冻结 Agent，强制保存会话现场（只冲刷已有缓冲，不重建快照），停止一切新增请求
 *
 * 弹性上限只回答"最多能同时开几个轻会话"，闸门回答"现在要不要收"；顺序固定：先闸门、后上限。
 * 读不到内存数据时按正常放行 + 默认上限，不因统计失败把所有任务卡死。
 */
internal object AgentMemoryGate {

    const val TIER_NORMAL = 0
    const val TIER_QUEUE = 1
    const val TIER_LOCKDOWN = 2
    const val TIER_FREEZE = 3

    const val QUEUE_AVAIL_MB = 1200L
    const val LOCKDOWN_AVAIL_MB = 800L
    const val FREEZE_AVAIL_MB = 500L

    /** 系统预留：扣掉它以后剩下的才算能分给会话的内存。 */
    const val SESSION_RESERVE_MB = 1500L

    /** 单会话基础预算：含模型响应缓冲、历史与工具输出。 */
    const val SESSION_BUDGET_MB = 500L

    const val MIN_SESSION_LIMIT = 1
    const val MAX_SESSION_LIMIT = 6

    /** 内存读数拿不到时的默认上限，取中间值，既不放飞也不卡死。 */
    const val DEFAULT_SESSION_LIMIT = 3

    /** 可用内存（MB）：取 /proc/meminfo 的 MemAvailable（与系统判定一致），读不到返回 -1。 */
    fun availableMb(): Long = runCatching {
        val lines = File("/proc/meminfo").readLines()
        fun read(key: String): Long? = lines
            .firstOrNull { line -> line.startsWith(key) }
            ?.filter { char -> char.isDigit() }
            ?.toLongOrNull()
        val kb = read("MemAvailable:") ?: read("MemFree:")
        if (kb == null) -1L else kb / 1024L
    }.getOrDefault(-1L)

    fun tier(): Int {
        val available = availableMb()
        return when {
            available in 0..FREEZE_AVAIL_MB -> TIER_FREEZE
            available in 0..LOCKDOWN_AVAIL_MB -> TIER_LOCKDOWN
            available in 0..QUEUE_AVAIL_MB -> TIER_QUEUE
            else -> TIER_NORMAL
        }
    }

    /**
     * 弹性会话上限 = clamp(floor((可用内存 - 1500MB) / 500MB), 1, 6)。
     * 只在新建会话与会话结束两个节点调用，运行中不重算，避免内存抖动把会话搅乱。
     */
    fun sessionLimit(): Int {
        val available = availableMb()
        if (available < 0) return DEFAULT_SESSION_LIMIT
        val raw = (available - SESSION_RESERVE_MB) / SESSION_BUDGET_MB
        return raw.coerceIn(MIN_SESSION_LIMIT.toLong(), MAX_SESSION_LIMIT.toLong()).toInt()
    }

    fun availableMb(context: Context): Long = availableMb()

    fun tier(context: Context): Int = tier()

    /** 一行摘要，写进自留日志，事后能看出是哪一档先触发。 */
    fun describe(): String =
        "剩余=${availableMb()}MB tier=${tier()} 上限=${sessionLimit()}"

    fun describe(context: Context): String = describe()
}
