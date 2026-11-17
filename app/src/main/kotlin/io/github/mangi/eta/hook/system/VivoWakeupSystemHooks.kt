package io.github.mangi.eta.hook.system

import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 在 system_server 侧拦截小 V 的唤醒服务启动，把入口让给 Eta。
 *
 * 为什么不在小 V 进程里做：那台机器上 LSPosed 注不进 `com.vivo.ai.copilot`
 * （`/proc/<pid>/maps` 里模块条目恒为 0，force-stop 重拉也一样），
 * 所以 `hook/vivo/VivoWakeupRedirectHooks.kt` 在真机上拿不到执行机会。
 * system_server 是唯一被证明能注入、且所有 startService 都必经的地方。
 *
 * 拦截语义：命中就返回 null（服务不启动），再以 system_server 身份拉起
 * [ETA_VOICE_ACTIVITY]。判断条件写得很窄——component 或 package 必须是小 V，
 * 且 action 落在已知唤醒集合里，避免误伤其它服务启动。
 */
internal object VivoWakeupSystemHooks {

    private const val ACTIVE_SERVICES_CLASS = "com.android.server.am.ActiveServices"
    private const val COPILOT_PACKAGE = "com.vivo.ai.copilot"
    private const val ETA_PACKAGE = "io.github.mangi.eta"
    private const val ETA_VOICE_ACTIVITY = "$ETA_PACKAGE.agent.voice.EtaVoiceAssistActivity"

    /** 上滑/Home 键唤出白条，action 后面可能带后缀，按前缀匹配。 */
    private const val WHITE_STRIP_PREFIX = "com.vivo.action.copilot.SHOW_WHITE_STRIP_BY_HOMEKEY"

    private val EXACT_ACTIONS = setOf(
        "com.vivo.ai.copilot.action.START_CIRCLE2SEARCH",
        "com.vivo.intent.action.WAKEUP_AGENT_BY_POWER",
        "com.vivo.intent.action.WAKEUP_AGENT_BY_POWER_CHARGE_START",
        "com.vivo.agent.action.wakeup_by_voice",
        "com.vivo.agent.action.wakeup_start_only",
        "com.vivo.intent.action_WAKE_UP_AGENT",
        "com.vivo.ai.copilot.action.ACTION_SYSTEM_BAR_CALL_PANEL",
        "android.intent.action.VOICE_COMMAND",
        "com.vivo.intent.action.WAKEUP_COPILOT_BY_KEYBOARD"
    )

    private const val LAUNCH_GRACE_MS = 1200L

    @Volatile
    private var lastLaunchAt = 0L

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "VivoWakeupSys")
        val logger = hooks.logger

        return hooks.install {
            val activeServices = HookSupport.findClassOrNull(classLoader, ACTIVE_SERVICES_CLASS)
            if (activeServices == null) {
                missing("vivo.wakeup.ams", ACTIVE_SERVICES_CLASS, "未找到 ActiveServices，跳过唤醒拦截")
                return@install
            }
            // 不同 ROM/Android 版本上 startServiceLocked 的签名差异很大，按名字取全部重载，
            // 只依赖「参数里有 Intent」这一个稳定事实。
            val candidates = activeServices.declaredMethods.filter { method ->
                method.name.contains("startServiceLocked") || method.name.contains("startServiceInnerLocked")
            }
            if (candidates.isEmpty()) {
                missing(
                    "vivo.wakeup.ams.method",
                    "$ACTIVE_SERVICES_CLASS#startServiceLocked",
                    "未找到 startServiceLocked，跳过唤醒拦截"
                )
                return@install
            }

            candidates.forEachIndexed { index, method ->
                intercept(
                    id = "vivo.wakeup.sys$index",
                    executable = method,
                    description = "ActiveServices#${method.name} 唤醒拦截"
                ) { chain ->
                    val intent = chain.args.firstOrNull { it is Intent } as? Intent
                    if (!shouldIntercept(intent)) {
                        return@intercept chain.proceed()
                    }
                    val launched = launch(chain.getThisObject(), intent?.action, logger)
                    // 启动成功才吞掉这次服务启动；失败就把控制权还回系统，
                    // 宁可让小 V 正常响应，也不要出现「按键完全没反应」。
                    if (launched) null else chain.proceed()
                }
            }
            logger.debug { "唤醒拦截已挂载: ${candidates.size} 个 startServiceLocked 重载" }
        }
    }

    /**
     * 只认「发往小 V 的唤醒 action」。
     *
     * component/package 必须指向小 V，或者 intent 没有显式 component 但 action
     * 属于小 V 私有命名空间——这两种情况才可能被误判，其余一律放行。
     */
    private fun shouldIntercept(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action ?: return false
        val matched = action.startsWith(WHITE_STRIP_PREFIX) || action in EXACT_ACTIONS
        if (!matched) return false
        val target = intent.component?.packageName ?: intent.`package`
        return target == null || target == COPILOT_PACKAGE
    }

    /**
     * 以 system_server 的 Context 拉起 Eta 的语音助理桥接页面。
     * `startActivityAsUser` 是 @hide API，只能反射调用。
     */
    private fun launch(activeServices: Any?, action: String?, logger: ModuleLogger): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < LAUNCH_GRACE_MS) return true
        val context = systemContext(activeServices, logger) ?: return false
        lastLaunchAt = now

        val intent = Intent().apply {
            setClassName(ETA_PACKAGE, ETA_VOICE_ACTIVITY)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        return runCatching {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val systemUser = userHandleClass.getField("SYSTEM").get(null)
            val method: Method = context.javaClass.getMethod(
                "startActivityAsUser",
                Intent::class.java,
                userHandleClass
            )
            method.invoke(context, intent, systemUser)
            logger.debug { "唤醒拦截: $action → EtaVoiceAssistActivity" }
            true
        }.getOrElse { exception ->
            logger.warn("唤醒拦截启动 Eta 失败: action=$action, type=${exception.javaClass.simpleName}")
            false
        }
    }

    /** ActiveServices 持有 mContext；拿不到就退回 ActivityThread 的 system context。 */
    private fun systemContext(activeServices: Any?, logger: ModuleLogger): Context? {
        if (activeServices != null) {
            runCatching {
                val field = activeServices.javaClass.getDeclaredField("mContext")
                field.isAccessible = true
                (field.get(activeServices) as? Context)?.let { return it }
            }.onFailure {
                logger.debug { "读取 ActiveServices.mContext 失败: ${it.javaClass.simpleName}" }
            }
        }
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            val getSystemContext = activityThread.getMethod("getSystemContext")
            getSystemContext.invoke(current) as? Context
        }.getOrNull()
    }
}
