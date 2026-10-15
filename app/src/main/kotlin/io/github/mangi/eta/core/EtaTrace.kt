package io.github.mangi.eta.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Eta 自留运行日志（环形落盘）。
 *
 * 为什么不靠 logcat：release 构建会删掉 VERBOSE/DEBUG，而且系统环形缓冲只留 2 分钟左右，
 * 出问题时现场常常已经被冲掉。这里把关键状态变化直接写进应用私有文件，诊断包随包带走。
 *
 * 只记录状态与原因，不记录会话正文、模型密钥或语音凭据。
 */
internal object EtaTrace {

    private const val FILE_NAME = "eta-trace.log"
    private const val MAX_BYTES = 512L * 1024
    private const val KEEP_BYTES = 256 * 1024
    private const val DUMP_MAX_CHARS = 200_000

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        log("app", "onCreate")
    }

    fun log(area: String, message: String) {
        val context = appContext ?: return
        val line = buildString {
            append(timeFormat.format(Date()))
            append(" [t").append(Thread.currentThread().id).append("] ")
            append(area).append(' ')
            append(message)
            append('\n')
        }
        runCatching {
            synchronized(lock) {
                val file = file(context)
                file.appendText(line, Charsets.UTF_8)
                if (file.length() > MAX_BYTES) trim(file)
            }
        }
    }

    /** 诊断包 state.txt 用的一行摘要。 */
    fun summary(context: Context): String = runCatching {
        val file = file(context)
        if (!file.exists() || file.length() == 0L) "无记录" else "${file.name}，${file.length()} 字节"
    }.getOrDefault("读取失败")

    /** 诊断包用：返回最近一段日志，带文件信息头。 */
    fun dump(context: Context): String = runCatching {
        synchronized(lock) {
            val file = file(context)
            if (!file.exists() || file.length() == 0L) return "（空）还没有记录。\n"
            buildString {
                append("== Eta 自留日志（文件 ").append(file.name).append("，")
                append(file.length()).append(" 字节）==\n")
                append(file.readText(Charsets.UTF_8).takeLast(DUMP_MAX_CHARS))
            }
        }
    }.getOrElse { "读取失败：" + it.javaClass.simpleName + "\n" }

    private fun trim(file: File) {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return
        val keep = text.takeLast(KEEP_BYTES)
        val firstBreak = keep.indexOf('\n')
        file.writeText(if (firstBreak > 0) keep.substring(firstBreak + 1) else keep, Charsets.UTF_8)
    }

    /** 记录两层调用方类名.方法名，用于排查"谁删了谁"；不含任何业务数据。 */
    fun callerHint(): String = runCatching {
        Throwable().stackTrace
            .dropWhile { frame -> frame.className.startsWith("io.github.mangi.eta.core.EtaTrace") }
            .take(2)
            .joinToString("<-") { frame ->
                frame.className.substringAfterLast('.') + "." + frame.methodName
            }
    }.getOrDefault("未知")

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
