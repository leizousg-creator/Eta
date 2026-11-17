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
            "ContextualSearch" to { ContextualSearchHooks.install(module, logger, classLoader) },
            "AssistantManager" to { AssistantManager.install(module, logger, classLoader) },
            "HotwordSelfHeal" to { HotwordSelfHealHooks.install(module, logger, classLoader) },
            "Power" to { PowerHooks.install(module, logger, classLoader) },
            "HyperOsPower" to { HyperOsPowerHooks.install(module, logger, classLoader) },
            "VirtualDisplay" to { VirtualDisplaySystemHooks.install(module, logger, classLoader) },
            "SuperX" to { SuperXValidatorHooks.install(module, logger, classLoader) },
            "VivoWakeupSys" to { VivoWakeupSystemHooks.install(module, logger, classLoader) }
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
