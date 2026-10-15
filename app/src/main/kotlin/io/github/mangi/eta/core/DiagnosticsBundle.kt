package io.github.mangi.eta.core

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.display.HelperExitLog
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 诊断包：把最近日志、root helper 退出现场与关键状态打包成一个 zip。
 *
 * 现场日志会被系统环形缓冲刷掉，出问题时往往已经追不回来；先落盘再排查，避免反复复现。
 * 只做只读采集，不修改任何设置，也不包含语音凭据。
 */
internal object DiagnosticsBundle {

    suspend fun collect(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val root = runCatching { RootAccess.isGranted }.getOrDefault(false)
        val entries = linkedMapOf<String, String>()
        entries["README.txt"] = README
        entries["state.txt"] = state(context, root)
        entries["helper-exit.txt"] = HelperExitLog.dump()
        entries["build.txt"] = apkIdentity(context)
        CrashRecorder.records(context).forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            entries["crash/" + file.name] = text.take(MAX_CRASH_CHARS)
        }
        entries["trace.txt"] = EtaTrace.dump(context)
        // 行为核查：把「隐形功能有没有真的跑起来」自动算好，用户不用自己盯。
        entries["行为核查.txt"] = BehaviorAudit.report(context)
        entries["logcat.txt"] = logcat(root)
        zip(entries)
    }

    fun defaultFileName(nowMillis: Long = System.currentTimeMillis()): String =
        "Eta-诊断包-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(nowMillis)) + ".zip"

    /** 装的是哪一包：大小、安装时间、版本号，比版本名可靠。 */
    private fun apkIdentity(context: Context): String = runCatching {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        val file = java.io.File(info.sourceDir)
        val installed = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(file.lastModified()))
        "包名：${context.packageName}\nversionName：${pkg.versionName}\nversionCode：${pkg.versionCode}\n" +
            "APK 大小：${file.length()} 字节\n安装时间：$installed"
    }.getOrElse { "读取 APK 信息失败：" + it.javaClass.simpleName }

    /** 日志分两段：先按 Eta 自己的进程精确采集（排查并发/中断就看这段），再补一段系统样本。 */
    private fun logcat(root: Boolean): String {
        val pid = android.os.Process.myPid()
        val etaLog = runCatching {
            command(listOf("logcat", "-d", "-v", "threadtime", "--pid=$pid", "-t", "20000"), 90)
        }.getOrElse { "读取失败：" + it.javaClass.simpleName }
        val systemArgs = if (root) {
            listOf("su", "-c", "logcat -d -v threadtime -t 4000")
        } else {
            // 无 root 时系统只交出本应用自己的日志，仍然保留现场的一部分。
            listOf("logcat", "-d", "-v", "threadtime", "-t", "2000")
        }
        val systemLog = runCatching { command(systemArgs, 60) }
            .getOrElse { "读取失败：" + it.javaClass.simpleName }
        return buildString {
            appendLine("== Eta 进程日志（pid=$pid，-t 20000）==")
            appendLine("实际覆盖：${window(etaLog)}")
            appendLine(etaLog.ifBlank { "（空）日志缓冲区可能已被刷掉。" })
            appendLine()
            appendLine("== 系统日志样本 ==")
            appendLine("实际覆盖：${window(systemLog)}")
            appendLine(systemLog.ifBlank { "（空）" })
        }.take(MAX_LOG_BYTES)
    }

    /** 从 threadtime 行取首末时间戳，如实标注实际覆盖的窗口，不再写死「最近 10 分钟」。 */
    private fun window(text: String): String {
        val stamps = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d)")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
        return if (stamps.isEmpty()) "无时间戳（可能为空）" else "${stamps.first()} → ${stamps.last()}"
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
                        if (output.size() < MAX_LOG_BYTES) output.write(buffer, 0, minOf(read, MAX_LOG_BYTES - output.size()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            drain.join(500)
            return output.toString("UTF-8") + "\n[日志读取超时，仅保留已取得的部分]"
        }
        drain.join(1000)
        return output.toString("UTF-8")
    }

    private suspend fun state(context: Context, root: Boolean): String = buildString {
        append("生成时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        append("Eta 版本：").append(versionName(context)).append('\n')
        append("包名：").append(context.packageName).append('\n')
        append("Android：").append(Build.VERSION.RELEASE).append("（SDK ").append(Build.VERSION.SDK_INT).append("）\n")
        append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("ABI：").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        append("Root：").append(if (root) "已授权" else "未授权").append('\n')

        val crashes = CrashRecorder.records(context)
        append("\n[崩溃记录]\n")
        append("已记录：").append(crashes.size).append(" 条（最多保留 ")
            .append(CrashRecorder.MAX_KEPT).append(" 条）\n")
        append("最近一份：").append(crashes.firstOrNull()?.name ?: "无").append('\n')

        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
        if (settings != null) {
            append("\n[虚拟屏]\n")
            append("开关：").append(settings.virtualScreenEnabled).append('\n')
            append("熄屏执行：").append(settings.virtualScreenOffEnabled).append('\n')
            append("主屏回退：").append(settings.virtualScreenFallbackEnabled).append('\n')
            append("换应用自动重启：").append(settings.virtualScreenAutoRestartApps).append('\n')
            append("空闲回收：").append(settings.virtualScreenIdleTimeoutMinutes).append(" 分钟\n")
            append("目标帧率：").append(settings.virtualScreenFrameRate).append('\n')
            append("小窗：").append(settings.virtualScreenFloatWindowEnabled)
                .append("，贴边折叠：").append(settings.virtualScreenFloatEdgeCollapseEnabled).append('\n')
        }

        val speech = runCatching { SpeechSettingsRepository.settings() }.getOrNull()
        if (speech != null) {
            append("\n[语音]\n")
            append("识别：").append(speech.asr).append("，播报：").append(speech.tts).append('\n')
            append("自动播报：").append(speech.autoSpeak).append('\n')
            append("语速：").append(speech.speechRate).append("，音调：").append(speech.speechPitch).append('\n')
            append("熄屏继续朗读：").append(speech.speakOnScreenOff).append('\n')
        }

        val memory = ActivityManager.MemoryInfo()
        runCatching {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        }
        append("\n[运行状态]\n")
        append("可用内存：").append(memory.availMem / 1024 / 1024).append(" MB / ")
            .append(memory.totalMem / 1024 / 1024).append(" MB")
            .append(if (memory.lowMemory) "（系统报告内存吃紧）" else "").append('\n')
        val battery = runCatching {
            context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        append("电量：").append(if (battery in 0..100) "$battery%" else "未知").append('\n')
        append("进程内存上限：").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append(" MB\n")

        append("\n[运行与无障碍]\n")
        val accessibility = runCatching {
            io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.isAvailable()
        }.getOrDefault(false)
        append("无障碍服务：").append(if (accessibility) "已连接" else "未连接（点屏幕类工具会失败）")
            .append('\n')
        append("自留日志：").append(EtaTrace.summary(context)).append('\n')
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName.orEmpty() + " (" + info.longVersionCode + ")"
    }.getOrDefault("未知")

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

    private const val MAX_LOG_BYTES = 2 * 1024 * 1024
    private const val MAX_CRASH_CHARS = 200_000

    private val README = """
        这是 Eta 生成的诊断包，内容全部只读采集：

        - state.txt：设备、虚拟屏、语音与运行状态快照
        - helper-exit.txt：虚拟屏服务进程（root helper）的 stderr 尾部与退出码
        - crash/*.txt：崩溃现场（每次崩溃一份，最多 10 份，含调用栈与日志尾部）
        - trace.txt：Eta 自留运行日志（按天保留最近 7 天；会话启停、取消原因、并发槽位、无障碍连接、虚拟屏服务启停）
        - 行为核查.txt：把「隐形功能有没有真的跑起来」自动算好（多开会话、并发排队、重任务互斥、进度通知、开机续跑、分享入口等），不用自己盯
        - logcat.txt：最近日志。已授权 root 时为全量日志，否则只有 Eta 自己的日志

        不含语音凭据、模型密钥与会话正文。排查完成后可直接删除。
    """.trimIndent() + "\n"
}
