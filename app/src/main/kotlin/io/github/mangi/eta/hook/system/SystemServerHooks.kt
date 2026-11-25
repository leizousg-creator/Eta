package io.github.mangi.eta.hook.system

import io.github.mangi.eta.hook.hyperos.HyperOsPowerHooks
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.ModuleLogger

import io.github.libxposed.api.XposedModule

internal object SystemServerHooks {

    /**
     * system_server 侧的全部 hook 组。
     *
     * 每组都用 runCatching 单独隔离：某些 ROM 类的反射探测会抛
     * `NoClassDefFoundError` / `LinkageError`，它们属于 Error 而不是 Exception，
     * 不会被 HookRegistrar 的功能组隔离层捕获。一旦冒泡出去，后面的组全部装不上，
     * 已注册的 hook 也会被框架判为失败而整体失效——所以这里必须兜住 Throwable。
     */
    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val groups: List<Pair<String, () -> HookInstallation>> = listOf(
            "AccessibilityProtection" to { AccessibilityProtectionHooks.install(module, logger, classLoader) },
            // 恢复上游行为：曾因误判「识屏失效」而停用，真凶是 VivoWakeupRedirectHooks
            // （小V 进程内拦 START_CIRCLE2SEARCH），与本组无关。
            "ContextualSearch" to { ContextualSearchHooks.install(module, logger, classLoader) },
            // 停用 AssistantManager。它会重建 IVoiceInteractionManagerService 的 Binder stub，
            // 而 vivo 的双指长按（唤醒小V + 圈选识别）正是走语音交互服务——
            // 被它改坏后手势直接无响应。实测：kill switch 全停后识屏恢复。
            // 该组的用途是"把 Eta 设为默认数字助理"，与现在的解耦方向相反，故永久移除。
            // "AssistantManager" to { AssistantManager.install(module, logger, classLoader) },
            "HotwordSelfHeal" to { HotwordSelfHealHooks.install(module, logger, classLoader) },
            "Power" to { PowerHooks.install(module, logger, classLoader) },
            "HyperOsPower" to { HyperOsPowerHooks.install(module, logger, classLoader) },
            "VirtualDisplay" to { VirtualDisplaySystemHooks.install(module, logger, classLoader) },
            "SuperX" to { SuperXValidatorHooks.install(module, logger, classLoader) }
            // 停用 VivoWakeupSys（含 hookJoviPowerKey）。
            //
            // 严重副作用实测：hookJoviPowerKey 里返回 null 跳过了
            // VivoInputPolicy.startJoviVoiceByPowerKey 的整个方法，而该方法同时负责
            // 消费电源键长按事件（mPowerKeyHandledByJovi=true）。事件未被消费，
            // 系统继续执行电源键长按的默认行为 —— 表现为「跳 Eta 后手机直接重启」。
            //
            // 结论：长按电源键接管在系统层不安全，永久停用。恢复系统原生行为。
            // "VivoWakeupSys" to { VivoWakeupSystemHooks.install(module, logger, classLoader) }
            // 停用 VivoWakeupSys：它拦的 START_CIRCLE2SEARCH 等 action 与 vivo
            // 双指识屏共用同一条通道，会把系统识屏截走变成跳 Eta。
            // 恢复时把下面这行改回 listOf 的正式条目即可。
            // "VivoWakeupSys" to { VivoWakeupSystemHooks.install(module, logger, classLoader) }
        )
        val installations = groups.mapNotNull { (name, block) ->
            runCatching { block() }.getOrElse { error ->
                logger.warn("Hook 组安装中断($name): ${error.javaClass.simpleName}")
                null
            }
        }
        return HookInstallation.combine(group = "SystemServer", installations = installations)
    }
}
