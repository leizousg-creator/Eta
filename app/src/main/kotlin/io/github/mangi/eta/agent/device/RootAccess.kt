package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal enum class RootAccessStatus { UNKNOWN, UNAVAILABLE, NOT_GRANTED, GRANTED, DENIED, TIMED_OUT }

internal data class RootAccessState(
    val status: RootAccessStatus = RootAccessStatus.UNKNOWN,
    val suPresent: Boolean = false,
    val isChecking: Boolean = false,
) {
    val isGranted: Boolean get() = status == RootAccessStatus.GRANTED
}

/** Root 探测归 App 进程所有；读取能力不会执行 su 或弹出授权。 */
internal object RootAccess {
    private const val PREFERENCES = "eta_root_access"
    private const val AUTOMATIC_REQUEST_ATTEMPTED = "automatic_request_attempted"
    private const val LAST_GRANTED = "last_granted"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RootAccessState())
    val state: StateFlow<RootAccessState> = mutableState.asStateFlow()
    val isGranted: Boolean get() = mutableState.value.isGranted
    private var preferences: SharedPreferences? = null
    private var probeJob: Job? = null

    fun initialize(context: Context) {
        synchronized(this) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
        refresh(context)
    }

    /** 首次发现 su 时请求一次；拒绝后只有显式 request 才会再次申请。 */
    fun refresh(context: Context): Job = startProbe(context, explicit = false)

    fun request(context: Context): Job = startProbe(context, explicit = true)

    /** 仅由执行器确认 su 拒绝后调用；普通命令失败不代表授权被撤销。 */
    fun markDenied() {
        preferences?.edit()?.putBoolean(LAST_GRANTED, false)?.apply()
        mutableState.value = mutableState.value.copy(status = RootAccessStatus.DENIED)
    }

    private fun startProbe(context: Context, explicit: Boolean): Job = synchronized(this) {
        probeJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val prefs = preferences ?: context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .also { preferences = it }
        mutableState.value = mutableState.value.copy(isChecking = true)
        scope.launch {
            try {
                val present = suExists()
                // 不再因 present=false 直接返回：KernelSU 只在授权后才把 su 注入进程 PATH，
                // 「先看到 su 才请求授权」会两边互相等待，导致永远检测不到 root。
                // 这里继续走请求流程，由 su 执行本身触发 root 管理器的授权。
                if (!present && !explicit && !rootManagerLikelyPresent()) {
                    mutableState.value = RootAccessState(RootAccessStatus.UNAVAILABLE)
                    return@launch
                }
                val attempted = prefs.getBoolean(AUTOMATIC_REQUEST_ATTEMPTED, false)
                val wasGranted = prefs.getBoolean(LAST_GRANTED, false)
                if (!shouldRequestRoot(explicit, attempted, wasGranted)) {
                    mutableState.value = mutableState.value.copy(
                        status = if (mutableState.value.status == RootAccessStatus.UNKNOWN) {
                            RootAccessStatus.NOT_GRANTED
                        } else {
                            mutableState.value.status
                        },
                        suPresent = true,
                    )
                    return@launch
                }
                // 先记住尝试，进程在授权弹窗期间退出也不会在下次启动再次打扰用户。
                if (!prefs.edit().putBoolean(AUTOMATIC_REQUEST_ATTEMPTED, true).commit()) {
                    mutableState.value = RootAccessState(RootAccessStatus.NOT_GRANTED, suPresent = true)
                    AndroidAgentLogger.warn("Root access probe outcome=preferences_unavailable")
                    return@launch
                }
                val result = runInterruptible {
                    BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { true }).use {
                        it.execute("id -u", timeoutMillis = 30_000L, maxOutputBytes = 256)
                    }
                }
                val granted = result.ok && result.stdout.trim() == "0"
                prefs.edit().putBoolean(LAST_GRANTED, granted).apply()
                mutableState.value = RootAccessState(
                    status = when {
                        granted -> RootAccessStatus.GRANTED
                        result.errorCode == "ROOT_UNAVAILABLE" -> RootAccessStatus.UNAVAILABLE
                        result.timedOut -> RootAccessStatus.TIMED_OUT
                        else -> RootAccessStatus.DENIED
                    },
                    suPresent = present || granted,
                )
            } finally {
                mutableState.value = mutableState.value.copy(isChecking = false)
            }
        }.also { probeJob = it }
    }

    /** KernelSU / Magisk / APatch 的工作目录存在，说明设备很可能装有 root 管理器。 */
    private fun rootManagerLikelyPresent(): Boolean = listOf(
        "/data/adb/ksu",
        "/data/adb/magisk",
        "/data/adb/ap",
    ).any { path -> runCatching { File(path).exists() }.getOrDefault(false) }

    /**
     * 候选 su 路径。
     *
     * 只查 PATH 是不够的：KernelSU 的 su 在 /data/adb/ksu/bin/su，只有被授权后才注入
     * 进程 PATH；而探测又要求先看到 su 才肯请求授权，两边互相等待会永远判定「不可用」。
     * 所以这里显式枚举各 root 方案的路径，命中任意一个即认为 su 存在。
     */
    private val SU_CANDIDATES = listOf(
        "/data/adb/ksu/bin/su",   // KernelSU
        "/system/bin/su",         // Magisk / 常见位置
        "/system/xbin/su",
        "/debug_ramdisk/su",      // APatch
        "/sbin/su",
    )

    private fun suExists(): Boolean {
        if (SU_CANDIDATES.any { path -> File(path).let { it.isFile && it.canExecute() } }) return true
        return System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .any { path -> File(path, "su").let { it.isFile && it.canExecute() } }
    }
}

internal fun shouldRequestRoot(explicit: Boolean, attempted: Boolean, wasGranted: Boolean): Boolean =
    explicit || !attempted || wasGranted
