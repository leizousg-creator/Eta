package io.github.mangi.eta.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Eta 自留运行日志（按天落盘，保留最近 7 天）。
 *
 * 为什么不靠 logcat：release 构建会删掉 VERBOSE/DEBUG，而且系统环形缓冲只留 2 分钟左右，
 * 出问题时现场常常已经被冲掉。这里把关键状态变化直接写进应用私有文件，诊断包随包带走。
 *
 * 保留策略从"按体积砍一半"改成"按天"：每天一个文件 `traces/eta-trace-yyyyMMdd.log`，
 * 只留最近 7 天（每天另有 2 MB 上限兜底，超过就当天裁到 1 MB）。
 * 这样"上周三到底触发没触发"这种翻旧账才有得查；轮转前的旧文件仍会被读到，不会丢。
 *
 * 只记录状态与原因，不记录会话正文、模型密钥或语音凭据。
 */
internal object EtaTrace {

    private const val DIR_NAME = "traces"
    private const val LEGACY_FILE_NAME = "eta-trace.log"
    private const val RETENTION_DAYS = 7
    private const val MAX_BYTES_PER_DAY = 2L * 1024 * 1024
    private const val KEEP_CHARS_PER_DAY = 1 * 1024 * 1024
    private const val DUMP_MAX_CHARS = 200_000

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val dayLabelFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastSweepDay: String = ""

    fun init(context: Context) {
        appContext = context.applicationContext
        log("app", "onCreate")
    }

    fun log(area: String, message: String) {
        val context = appContext ?: return
        val now = Date()
        val line = buildString {
            append(timeFormat.format(now))
            append(" [t").append(Thread.currentThread().id).append("] ")
            append(area).append(' ')
            append(message)
            append('\n')
        }
        runCatching {
            synchronized(lock) {
                val day = dayFormat.format(now)
                val file = fileFor(context, day)
                file.appendText(line, Charsets.UTF_8)
                if (file.length() > MAX_BYTES_PER_DAY) trim(file)
                sweep(context, day)
            }
        }
    }

    /** 诊断包 state.txt 用的一行摘要。 */
    fun summary(context: Context): String = runCatching {
        val days = dayFiles(context)
        val legacy = legacyFile(context).takeIf { it.exists() && it.length() > 0L }
        when {
            days.isEmpty() && legacy == null -> "无记录"
            else -> buildString {
                append("按天保留 ").append(days.size).append('/').append(RETENTION_DAYS).append(" 天")
                append("，共 ").append(days.sumOf { it.length() }).append(" 字节")
                if (days.isNotEmpty()) {
                    append("（").append(dayLabel(days.first())).append(" ~ ")
                        .append(dayLabel(days.last())).append("）")
                }
                if (legacy != null) append("，另有轮转前旧文件 ").append(legacy.length()).append(" 字节")
            }
        }
    }.getOrDefault("读取失败")

    /** 诊断包用：返回最近一段日志，带文件信息头。 */
    fun dump(context: Context): String = runCatching {
        synchronized(lock) {
            val perDay = dailyDumps(context)
            if (perDay.isEmpty()) return "（空）还没有记录。\n"
            val body = buildString {
                append("== Eta 自留日志（按天保留最近 ").append(RETENTION_DAYS)
                    .append(" 天，实际 ").append(perDay.size).append(" 天）==\n")
                perDay.forEach { (label, text) ->
                    append("---- ").append(label).append(" ----\n")
                    append(text)
                    if (!text.endsWith("\n")) append('\n')
                }
            }
            if (body.length > DUMP_MAX_CHARS) body.takeLast(DUMP_MAX_CHARS) else body
        }
    }.getOrElse { "读取失败：" + it.javaClass.simpleName + "\n" }

    /** 逐天返回（日期标签 -> 文本，旧的在前），供行为核查做按天统计。 */
    fun dailyDumps(context: Context): List<Pair<String, String>> = runCatching {
        synchronized(lock) {
            val result = mutableListOf<Pair<String, String>>()
            legacyFile(context).takeIf { it.exists() && it.length() > 0L }?.let { legacy ->
                val text = runCatching { legacy.readText(Charsets.UTF_8) }.getOrDefault("")
                result += "轮转前旧文件" to text
            }
            dayFiles(context).forEach { file ->
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                result += dayLabel(file) to text
            }
            result
        }
    }.getOrDefault(emptyList())

    /** 记录两层调用方类名.方法名，用于排查"谁删了谁"；不含任何业务数据。 */
    fun callerHint(): String = runCatching {
        Throwable().stackTrace
            .dropWhile { frame -> frame.className.startsWith("io.github.mangi.eta.core.EtaTrace") }
            .take(2)
            .joinToString("<-") { frame ->
                frame.className.substringAfterLast('.') + "." + frame.methodName
            }
    }.getOrDefault("未知")

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun legacyFile(context: Context): File = File(context.filesDir, LEGACY_FILE_NAME)

    private fun fileFor(context: Context, day: String): File {
        val directory = dir(context)
        if (!directory.exists()) directory.mkdirs()
        return File(directory, "eta-trace-$day.log")
    }

    /** 从旧到新列出按天日志。 */
    private fun dayFiles(context: Context): List<File> =
        dir(context)
            .listFiles { file: File ->
                file.isFile && file.name.startsWith("eta-trace-") && file.name.endsWith(".log")
            }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun dayLabel(file: File): String {
        val raw = file.name.removePrefix("eta-trace-").removeSuffix(".log")
        return runCatching {
            val parsed = SimpleDateFormat("yyyyMMdd", Locale.US).parse(raw)
            if (parsed == null) raw else dayLabelFormat.format(parsed)
        }.getOrDefault(raw)
    }

    /** 每天最多清理一次：只留最近 RETENTION_DAYS 天。 */
    private fun sweep(context: Context, today: String) {
        if (lastSweepDay == today) return
        lastSweepDay = today
        dayFiles(context).dropLast(RETENTION_DAYS).forEach { it.delete() }
    }

    /** 当天写太多时的兜底裁剪（按字符近似，中文偏保守）。 */
    private fun trim(file: File) {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return
        val keep = text.takeLast(KEEP_CHARS_PER_DAY)
        val firstBreak = keep.indexOf('\n')
        file.writeText(if (firstBreak > 0) keep.substring(firstBreak + 1) else keep, Charsets.UTF_8)
    }
}
