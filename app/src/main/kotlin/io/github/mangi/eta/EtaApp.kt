package io.github.mangi.eta

import io.github.mangi.eta.agent.voice.SpeechOssUpload
import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.display.VirtualScreenFloatWindow
import io.github.mangi.eta.agent.display.VscrDiag
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.CrashRecorder
import io.github.mangi.eta.agent.automation.AutoBackupAlarm
import io.github.mangi.eta.data.repository.AutoBackupRunner
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        // 崩溃留证：任何进程一启动就装好，写入失败也不影响原有崩溃流程。
        runCatching { CrashRecorder.install(this) }
        // 自留日志：任何进程启动就落盘，诊断包不再依赖 logcat 的 2 分钟缓冲。
        runCatching { io.github.mangi.eta.core.EtaTrace.init(this) }
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        // 文本库体量：升级后第一次起动把超上限的历史字段按写入侧规则保尾截断（只跑一次）。
        applicationScope.launch {
            runCatching { io.github.mangi.eta.data.db.TextStoreMaintenance.runOnceIfNeeded(this@EtaApp) }
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        applicationScope.launch { TerminalRuntime.backgroundJobs(this@EtaApp).resume() }
        SettingsDataStore.init(this)
        runCatching { VscrDiag.init(this) }
        VirtualScreenFloatWindow.init(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        // 悬浮胶囊常驻：上次开着就继续挂上，没有任务时显示空闲。
        applicationScope.launch(Dispatchers.IO) {
            val pinned = runCatching { SettingsDataStore.settings().overlayCapsulePinned }.getOrDefault(false)
            if (pinned) {
                runCatching {
                    startService(
                        android.content.Intent(
                            this@EtaApp,
                            io.github.mangi.eta.agent.runtime.AgentRuntimeService::class.java,
                        ).setAction(io.github.mangi.eta.agent.runtime.AgentRuntimeService.ACTION_PIN_CAPSULE),
                    )
                }
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                io.github.mangi.eta.data.db.EtaDatabase.get(this@EtaApp).agentTaskDao().recoverInterrupted(System.currentTimeMillis())
                io.github.mangi.eta.agent.automation.AgentTaskScheduler.enqueueDue(this@EtaApp)
                io.github.mangi.eta.agent.automation.AgentTaskScheduler.refresh(this@EtaApp)
                // 自动备份：先排下一次唤醒（闹钟可能已被系统清掉），再补一次到期检查。
                AutoBackupAlarm.refresh(this@EtaApp)
                AutoBackupRunner.maybeRun(this@EtaApp)
                // 诊断包每周自动导出一次，直接落到「诊断包备份」。
                io.github.mangi.eta.core.DiagnosticsAutoExport.refresh(this@EtaApp)
            }.onFailure { AndroidAgentLogger.warn("Automation recovery unavailable") }
        }
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            try {
                SpeechOssUpload(this@EtaApp).retryPending(this@EtaApp)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn("Eta speech cleanup unavailable: type=${error.javaClass.simpleName}")
            }
        }
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileVendorAssistantPreferences(service)
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
