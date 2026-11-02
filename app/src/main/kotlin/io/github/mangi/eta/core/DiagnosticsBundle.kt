package io.github.mangi.eta.core

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 诊断包：把现场状态与最近日志打成一份 zip。
 *
 * 为什么要有：日志环形缓冲会被刷掉，出问题时往往已经追不回来；先落盘再排查，
 * 省掉反复复现。只做只读采集，不改设置、不含密钥与对话正文。
 *
 * 采集项：
 *  - state.txt   版本、机型、ABI、root、无障碍、内存、电量、关键设置摘要
 *  - logcat.txt  Eta 进程日志（精确到 pid）+ 系统日志样本
 *  - crash.txt   崩溃缓冲区（-b crash）
 *  - README.txt  说明
 */
internal object DiagnosticsBundle {

    /** 默认落盘目录；写不进去回退到应用私有目录。 */
    const val DEFAULT_DIR = "/storage/emulated/0/Download/Eta诊断包"

    private const val KEEP_FILES = 5
    private const val MAX_LOG_CHARS = 1_500_000

    suspend fun collect(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val root = runCatching { RootAccess.isGranted }.getOrDefault(false)
        val entries = linkedMapOf<String, String>()
        entries["README.txt"] = README
        entries["state.txt"] = state(context, root)
        entries["logcat.txt"] = logcat(root)
        entries["crash.txt"] = command(
            listOf("logcat", "-d", "-b", "crash", "-v", "threadtime", "-t", "4000"),
            timeoutSeconds = 60,
        ).ifBlank { "（崩溃缓冲区为空）\n" }
        zip(entries)
    }

    /** 采集并落盘，返回写好的文件；目录不可写时退回私有目录。 */
    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        val bytes = collect(context)
        val preferred = File(DEFAULT_DIR)
        val dir = if (runCatching { preferred.mkdirs(); preferred.isDirectory && preferred.canWrite() }.getOrDefault(false)) {
            preferred
        } else {
            File(context.filesDir, "diagnostics").apply { mkdirs() }
        }
        val file = File(dir, defaultFileName())
        file.writeBytes(bytes)
        prune(dir)
        file
    }

    fun defaultFileName(nowMillis: Long = System.currentTimeMillis()): String =
        "Eta-diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(nowMillis)) + ".zip"

    private fun prune(dir: File) {
        runCatching {
            dir.listFiles()
                ?.filter { it.name.startsWith("Eta-diagnostics-") && it.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP_FILES)
                ?.forEach { it.delete() }
        }
    }

    private suspend fun state(context: Context, root: Boolean): String = buildString {
        append("生成时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        append("Eta 版本：").append(versionName(context)).append('\n')
        append("包名：").append(context.packageName).append('\n')
        append("Android：").append(Build.VERSION.RELEASE).append("（SDK ").append(Build.VERSION.SDK_INT).append("）\n")
        append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("ABI：").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        append("Root：").append(if (root) "已授权" else "未授权").append('\n')
        append("无障碍服务：")
            .append(if (AgentAccessibilityService.isAvailable()) "已连接" else "未连接（点屏幕类工具会失败）")
            .append('\n')

        val memory = ActivityManager.MemoryInfo()
        runCatching { context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory) }
        append("\n[运行状态]\n")
        append("可用内存：").append(memory.availMem / 1024 / 1024).append(" MB / ")
            .append(memory.totalMem / 1024 / 1024).append(" MB")
            .append(if (memory.lowMemory) "（系统报告内存吃紧）" else "").append('\n')
        val battery = runCatching {
            context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        append("电量：").append(if (battery in 0..100) "$battery%" else "未知").append('\n')
        append("进程内存上限：").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append(" MB\n")

        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
        if (settings != null) {
            append("\n[设置摘要]\n")
            append("记忆：").append(settings.memoryEnabled).append("，自动记忆：").append(settings.autoMemoryEnabled)
                .append("，自动技能：").append(settings.autoSkillsEnabled).append('\n')
            append("主题：").append(settings.appearance.themeMode).append('\n')
            append("出站词替换：").append(settings.outbound.enabled)
                .append("（规则 ").append(settings.outbound.rules.size).append(" 条，改系统提示：")
                .append(settings.outbound.applyToSystem).append("）\n")
            append("自动续写：").append(settings.outbound.autoContinueEnabled)
                .append("（最多 ").append(settings.outbound.maxAutoContinues).append(" 次）\n")
        }

        val skillsDir = File(context.filesDir, "skills")
        append("\n[技能]\n")
        append("已装技能目录：")
            .append(skillsDir.listFiles()?.count { it.isDirectory } ?: 0)
            .append(" 个\n")
        append("提词包正文文件：")
            .append(File(context.filesDir, "promptkit/versions").listFiles()?.size ?: 0)
            .append(" 个\n")
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName.orEmpty() + " (" + info.longVersionCode + ")"
    }.getOrDefault("未知")

    /** 进程日志精确到 pid，再补一段系统样本；只保留尾部，排查看的是崩溃前后那几百行。 */
    private fun logcat(root: Boolean): String {
        val pid = android.os.Process.myPid()
        val appLog = runCatching {
            command(listOf("logcat", "-d", "-v", "threadtime", "--pid=$pid", "-t", "20000"), 90)
        }.getOrElse { "读取失败：" + it.javaClass.simpleName }
        val systemLog = runCatching {
            val args = if (root) {
                listOf("su", "-c", "logcat -d -v threadtime -t 4000")
            } else {
                listOf("logcat", "-d", "-v", "threadtime", "-t", "2000")
            }
            command(args, 60)
        }.getOrElse { "读取失败：" + it.javaClass.simpleName }
        val text = buildString {
            appendLine("== Eta 进程日志（pid=$pid）==")
            appendLine(appLog.ifBlank { "（空）日志缓冲区可能已被刷掉。" })
            appendLine()
            appendLine("== 系统日志样本 ==")
            appendLine(systemLog.ifBlank { "（空）" })
        }
        return if (text.length <= MAX_LOG_CHARS) text else {
            "[日志过长，已保留尾部 $MAX_LOG_CHARS 字符]\n" + text.takeLast(MAX_LOG_CHARS)
        }
    }

    private fun command(args: List<String>, timeoutSeconds: Long): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val drain = Thread {
            runCatching {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (output.size() < MAX_LOG_CHARS) {
                            output.write(buffer, 0, minOf(read, MAX_LOG_CHARS - output.size()))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            drain.join(500)
            return output.toString("UTF-8") + "\n[读取超时，仅保留已取得的部分]"
        }
        drain.join(1000)
        return output.toString("UTF-8")
    }

    private fun zip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val README = """
        这是 Eta 生成的诊断包，内容全部只读采集：

        - state.txt   版本、机型、ABI、root、无障碍、内存、电量与关键设置摘要
        - logcat.txt  Eta 进程日志（精确到 pid）+ 系统日志样本
        - crash.txt   系统崩溃缓冲区

        不含模型密钥、MCP 凭据、语音凭据与会话正文。
        排查完成后可直接删除；同一目录只保留最近 $KEEP_FILES 份。
    """.trimIndent() + "\n"
}
