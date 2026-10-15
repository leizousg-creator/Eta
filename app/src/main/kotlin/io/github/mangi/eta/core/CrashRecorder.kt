package io.github.mangi.eta.core

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 崩溃留证：把未捕获异常的现场落盘到 `files/crash/`，只保留最近若干份。
 *
 * 现场日志会被系统环形缓冲刷掉，而崩溃往往只复现一次；先落盘再排查，避免反复等待复现。
 * 只做记录：写入后仍交给系统原有的兜底处理器，不吞异常、不改变崩溃行为。
 */
internal object CrashRecorder {

    /** 最多保留的崩溃记录份数，超出的按文件名（时间）从旧到新删除。 */
    const val MAX_KEPT = 10

    /** 崩溃记录最长保留天数：份数和天数谁先到就按谁删。 */
    private const val RETENTION_DAYS = 7

    private const val DIR_NAME = "crash"
    private const val EXTENSION = ".txt"
    private const val MAX_LOGCAT_BYTES = 256 * 1024

    @Volatile
    private var installed = false

    /** 安装兜底处理器；重复调用只生效一次。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { capture(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun directory(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 崩溃记录，最新在前。 */
    fun records(context: Context): List<File> =
        directory(context)
            .listFiles { file: File -> file.isFile && file.name.endsWith(EXTENSION) }
            ?.sortedByDescending { it.name }
            .orEmpty()

    fun latest(context: Context): File? = records(context).firstOrNull()

    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    /** 写一份崩溃记录并做保留份数清理。 */
    fun capture(context: Context, thread: Thread, error: Throwable): File {
        val dir = directory(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "crash-$stamp$EXTENSION")
        target.writeText(report(context, thread, error), Charsets.UTF_8)
        prune(dir)
        // 崩溃现场顺手补一次巡检取证：独立线程，失败不影响记崩溃。
        runCatching { DiagnosticsPatrol.captureNow(context, "崩溃即取证") }
        return target
    }

    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS.toLong())
        dir.listFiles { file: File -> file.isFile && file.name.endsWith(EXTENSION) }
            ?.sortedByDescending { it.name }
            .orEmpty()
            .forEachIndexed { index, file ->
                // 份数超了，或者超过 7 天，都删；两者谁先到算谁。
                if (index >= MAX_KEPT || file.lastModified() < cutoff) file.delete()
            }
    }

    private fun report(context: Context, thread: Thread, error: Throwable): String = buildString {
        append("时间：")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append('\n')
        append("线程：").append(thread.name).append('\n')
        append("异常：").append(error.javaClass.name).append('\n')
        append("信息：").append(error.message ?: "(无)").append('\n')
        append("版本：").append(versionName(context)).append('\n')
        append("Android：").append(Build.VERSION.RELEASE)
            .append("（SDK ").append(Build.VERSION.SDK_INT).append("）\n")
        append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("进程：").append(processName(context)).append("\n\n")
        append("=== 调用栈 ===\n")
        append(error.stackTraceToString())
        append("\n=== 日志尾部 ===\n")
        append(logcatTail())
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName.orEmpty() + " (" + info.longVersionCode + ")"
    }.getOrDefault("未知")

    private fun processName(context: Context): String = runCatching {
        Application.getProcessName()
    }.getOrNull() ?: context.packageName

    private fun logcatTail(): String {
        // 以前只抓全系统尾部，本进程自己的日志常被刷光——报告里一条 app 行都没有，
        // 只能靠系统侧 START 记录反推触发点。这里先单独抓本进程 pid 的日志，再补全系统尾部。
        val pid = android.os.Process.myPid()
        val own = runCatching {
            command(listOf("su", "-c", "logcat -d -v threadtime -t 400 --pid=$pid"))
        }.getOrNull()
        val global = runCatching {
            command(listOf("su", "-c", "logcat -d -v threadtime -t 400"))
        }.getOrNull()
        val plain = runCatching {
            command(listOf("logcat", "-d", "-v", "threadtime", "-t", "200"))
        }.getOrNull()
        val systemTail = when {
            !global.isNullOrBlank() -> global
            !plain.isNullOrBlank() -> plain
            else -> null
        }
        return buildString {
            if (!own.isNullOrBlank()) {
                append("--- 本进程（pid=").append(pid).append("）---\n").append(own).append('\n')
            }
            append("--- 全系统尾部 ---\n")
            append(systemTail ?: "日志读取失败。")
        }
    }

    private fun command(args: List<String>): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val drain = Thread {
            runCatching {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (output.size() < MAX_LOGCAT_BYTES) {
                            output.write(buffer, 0, minOf(read, MAX_LOGCAT_BYTES - output.size()))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(6, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            drain.join(300)
            return output.toString("UTF-8")
        }
        drain.join(500)
        return output.toString("UTF-8")
    }
}
