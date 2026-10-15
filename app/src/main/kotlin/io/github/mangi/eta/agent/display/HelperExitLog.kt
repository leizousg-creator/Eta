package io.github.mangi.eta.agent.display

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * root helper 的退出现场：虚拟屏服务进程的 stderr 尾部与实际退出码。
 *
 * 以前 stderr 只被排空不保留，进程一挂就只剩"虚拟屏不可用"，无法区分是 native 崩溃、
 * 被系统回收还是主动退出。这里保留最近几次会话的尾部输出，供诊断包一键带走。
 */
internal object HelperExitLog {
    private const val MAX_SESSIONS = 5
    private const val MAX_LINE_CHARS = 400
    private const val MAX_SESSION_CHARS = 6_000

    private val lock = Any()
    private val sessions = ArrayDeque<Session>()
    private var current: Session? = null

    private class Session(val startedAt: Long) {
        val lines = StringBuilder()
        var exitCode: Int? = null
        var finishedAt: Long = 0L
    }

    /** 新建虚拟屏会话时调用：上一段没结束的记录一并收尾，避免下一次会话覆盖现场。 */
    fun started() = synchronized(lock) {
        current?.let { open -> if (open.exitCode == null) open.exitCode = STILL_RUNNING }
        val session = Session(System.currentTimeMillis())
        current = session
        sessions.addLast(session)
        while (sessions.size > MAX_SESSIONS) sessions.removeFirst()
    }

    /** 记录一行 helper stderr；超长行与疑似 base64 数据一律丢弃，只留诊断文本。 */
    fun stderr(line: String) = synchronized(lock) {
        val session = current ?: return@synchronized
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LINE_CHARS) return@synchronized
        if (looksLikePayload(trimmed)) return@synchronized
        if (session.lines.length > MAX_SESSION_CHARS) return@synchronized
        session.lines.append(trimmed).append('\n')
    }

    /** helper 进程结束时调用，并顺带把退出原因记进系统日志。 */
    fun finished(exitCode: Int) = synchronized(lock) {
        val session = current ?: return@synchronized
        session.exitCode = exitCode
        session.finishedAt = System.currentTimeMillis()
    }

    fun dump(): String = synchronized(lock) {
        if (sessions.isEmpty()) return "本次运行还没有启动过虚拟屏服务进程。\n"
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        buildString {
            append("虚拟屏服务进程（root helper）退出记录，最多保留最近 ").append(MAX_SESSIONS).append(" 次。\n\n")
            sessions.toList().asReversed().forEach { session ->
                append("── 会话 ").append(format.format(Date(session.startedAt)))
                append(" 退出码 ").append(session.exitCode?.toString() ?: "运行中")
                append("（").append(describe(session.exitCode)).append("）\n")
                if (session.lines.isEmpty()) {
                    append("   stderr 无输出\n")
                } else {
                    session.lines.toString().trimEnd('\n').lineSequence().forEach { append("   ").append(it).append('\n') }
                }
                append('\n')
            }
        }
    }

    private fun looksLikePayload(line: String): Boolean {
        if (line.length < 200) return false
        return line.count { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' } >= line.length - line.count { it == ' ' }
    }

    private fun describe(exitCode: Int?): String = when (exitCode) {
        null, STILL_RUNNING -> "仍在运行"
        0 -> "正常退出"
        137 -> "被 SIGKILL 终止（通常是被系统回收或内存超限）"
        139 -> "native 崩溃 SIGSEGV（段错误，多为帧缓冲越界等底层问题）"
        134 -> "native 崩溃 SIGABRT"
        143 -> "被 SIGTERM 终止"
        else -> if (exitCode == 0) "正常退出" else "异常退出"
    }

    private const val STILL_RUNNING = Int.MIN_VALUE
}
