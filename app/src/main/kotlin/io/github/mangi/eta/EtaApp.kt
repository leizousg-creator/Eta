package io.github.mangi.eta

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.mangi.eta.agent.approval.AgentApprovalBroker
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.data.repository.AgentApprovalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AppStrings
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    /** 仅主进程创建；`:voice` / `:recognition` 等辅助进程保持为 null。 */
    internal var container: AppContainer? = null
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        AppStrings.init(this)
        Prefs.initLocal(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        container = AppContainer(this).also(AppContainer::initialize)
        // ETA-FORK: 提示词包存储
        io.github.mangi.eta.data.promptkit.PromptKitBridge.init(this)
        // ETA-FORK: 审批需要 Application 上下文，档位快照供工具执行线程同步读取
        AgentApprovalBroker.initialize(this)
        applicationScope.launch {
            AgentApprovalRepository.flow().collect { settings -> AgentApprovalRepository.publish(settings) }
        }
        // ETA-FORK: 恢复 root 命令档位
        io.github.mangi.eta.fork.command.DeviceCommandPolicy.restore(this@EtaApp)
        // ETA-FORK: 到期自动导出一次（6 小时 / 保留 3 份）
        applicationScope.launch {
            io.github.mangi.eta.fork.backup.AutoBackup.maybeRun(this@EtaApp)
        }
        // ETA-FORK: 自建模块只在真实进程启用，Robolectric 下保持上游行为
        if (!android.os.Build.FINGERPRINT.startsWith("robolectric")) {
            io.github.mangi.eta.core.ModuleFlags.toolsEnabled = true
        }
        XposedServiceHelper.registerListener(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            BrowserSessionRegistry.releaseIdlePages()
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
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
