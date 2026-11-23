package io.github.mangi.eta.core

import io.github.mangi.eta.agent.device.RootAccess
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hook 安装自检：把各注入进程的安装结果汇总给用户看。
 *
 * 为什么走 logcat 而不是写文件：注入进程（system_server / SystemUI / 小 V）是
 * uid 1000 或对方应用身份，写不了 Eta 的私有目录；`Prefs` 的 RemotePreferences
 * 只有读取方向。日志是唯一「任何进程都能写、我们都读得到」的通道。
 *
 * 只读、不产生副作用；root 可用时读全量缓冲，否则退回本进程可见范围。
 */
internal object HookSelfCheck {

    /** logcat 里能证明「装了什么、缺了什么」的标记词，与 [ModuleMain] 的日志保持一致。 */
    private val MARKERS = listOf(
        "Hook 安装完成",
        "Hook 组安装失败",
        "模块已加载",
        "跳过全部 hook",
        "未找到",
    )

    private const val DEFAULT_MAX_LINES = 60

    data class Snapshot(
        val lines: List<String>,
        val root: Boolean,
        val error: String? = null,
    ) {
        val installedGroups: Int
            get() = lines.count { it.contains("Hook 安装完成") }
    }

    suspend fun collect(maxLines: Int = DEFAULT_MAX_LINES): Snapshot = withContext(Dispatchers.IO) {
        val root = runCatching { RootAccess.isGranted }.getOrDefault(false)
        val raw = try {
            readLogcat(root)
        } catch (exception: Throwable) {
            return@withContext Snapshot(emptyList(), root, exception.javaClass.simpleName)
        }
        val picked = raw.lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && MARKERS.any { line.contains(it) } }
            .toList()
            .takeLast(maxLines)
        Snapshot(picked, root)
    }

    /** 应急开关状态：文件在不在，比任何 hook 都可靠。 */
    fun killSwitchState(): String =
        if (HookKillSwitch.isHookDisabled()) {
            "已停用（${HookKillSwitch.activeOffFile() ?: "hook_off"} 存在）"
        } else {
            "正常"
        }

    /** 给人看的整段文本：设置页复制、诊断包里都用这一份。 */
    fun format(snapshot: Snapshot): String = buildString {
        appendLine("应急开关：${killSwitchState()}")
        appendLine("root：${if (snapshot.root) "可用" else "不可用"}")
        snapshot.error?.let { appendLine("读取失败：$it") }
        if (snapshot.lines.isEmpty()) {
            appendLine("没有 hook 记录：装完模块后需要重启手机，让 system_server 重新注入后再看。")
            appendLine("（普通应用进程的注入日志保留时间更长，system_server 那条通常只在开机后出现一次。）")
        } else {
            appendLine("最近 ${snapshot.lines.size} 条（进程 → 安装结果）：")
            snapshot.lines.forEach { appendLine(it) }
        }
    }

    private fun readLogcat(root: Boolean): String {
        // -s Eta：只取本模块 tag，避免把几万行系统日志读进内存。
        val command = if (root) {
            listOf("su", "-c", "logcat -d -v brief -s Eta -t 6000")
        } else {
            listOf("logcat", "-d", "-v", "brief", "-s", "Eta", "-t", "6000")
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(20, TimeUnit.SECONDS)
        return text
    }
}
