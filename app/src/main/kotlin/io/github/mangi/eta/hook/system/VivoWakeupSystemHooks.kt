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
    private const val ETA_VOICE_ACTIVITY = "$ETA_PACKAGE.ui.MainActivity"

    /** 上滑/Home 键唤出白条，action 后面可能带后缀，按前缀匹配。 */
    private const val WHITE_STRIP_PREFIX = "com.vivo.action.copilot.SHOW_WHITE_STRIP_BY_HOMEKEY"

    /**
     * 需要重定向的唤醒动作。
     *
     * 刻意**不含** `com.vivo.agent.action.wakeup_by_voice` 与 `wakeup_start_only`：
     * 那两个由 `com.vivo.voicewakeup` 的硬件 hotword 引擎发出（日志
     * `sound_trigger_hw_default: wakeup event by yuemeng`），环境音误判也会触发。
     * 一旦拦下来拉起 Eta，用户在任何应用里都会被突然弹出的全屏窗口打断。
     * 语音入口留给系统原生处理，按键类入口才重定向。
     */
    private val EXACT_ACTIONS = setOf(
        "com.vivo.ai.copilot.action.START_CIRCLE2SEARCH",
        "com.vivo.intent.action.WAKEUP_AGENT_BY_POWER",
        "com.vivo.intent.action.WAKEUP_AGENT_BY_POWER_CHARGE_START",
        "com.vivo.intent.action_WAKE_UP_AGENT",
        "com.vivo.ai.copilot.action.ACTION_SYSTEM_BAR_CALL_PANEL",
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
            // 不要用 declaredMethods 枚举：那会解析类的全部方法签名，system_server 里
            // 一旦碰到加载不了的 @hide 类型就抛 NoClassDefFoundError（Error，不属于
            // Exception，HookRegistrar 不会捕获），整个 SystemServerHooks 安装被打断，
            // 连前面已注册的 SuperX 白名单一起失效。
            // 改为按已知签名逐个精确查找，HookSupport.findMethod 内部已 catch LinkageError。
            val candidates = findStartServiceMethods(activeServices)
            if (candidates.isEmpty()) {
                missing(
                    "vivo.wakeup.ams.method",
                    "$ACTIVE_SERVICES_CLASS#startServiceLocked",
                    "未找到 startServiceLocked 签名，跳过唤醒拦截"
                )
                return@install
            }

            candidates.forEachIndexed { index, method ->
                runCatching {
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
                }.onFailure {
                    logger.warn("挂载唤醒拦截失败(${method.name}): ${it.javaClass.simpleName}")
                }
            }
            logger.debug { "唤醒拦截已挂载: ${candidates.size} 个 startServiceLocked 签名" }

            // 临时停用（实验）：确认「双指识屏跳 Eta」是否由它造成。
            // 系统在 canCircleSearchAction=false 时会回落到 Jovi 语音，
            // 而那条回落路径同样经过 startJoviVoiceByPowerKey，被这里截走。
            // 先关掉验证因果，确认后再决定是窄化条件还是放弃侧键接管。
            // hookJoviPowerKey(classLoader, logger)
        }
    }

    /**
     * 接管长按电源键。
     *
     * vivo 在 `VivoInputPolicy` 里硬编码 `startJoviVoiceByPowerKey`（日志：
     * `_V_VivoInputPolicy: startJoviVoiceByPowerKey mPowerKeyHandledByJovi=true`），
     * 直接点名 Jovi，既不查"谁是助理"也不经过 WakeupService，
     * 所以 ActiveServices 那套拦截完全管不到它。这里按方法名精确接管。
     *
     * 类名在不同 ROM 版本有差异，逐个尝试；找不到就记 missing，让长按继续归小 V。
     */
    private fun HookRegistrar.hookJoviPowerKey(classLoader: ClassLoader, logger: ModuleLogger) {
        val classNames = listOf(
            "com.android.server.policy.VivoInputPolicy",
            "com.android.server.policy.VivoInputPolicyImpl",
            "com.android.server.policy.VivoInputPolicyManager",
        )
        for (className in classNames) {
            val clazz = HookSupport.findClassOrNull(classLoader, className) ?: continue
            val method = HookSupport.findMethod(clazz, "startJoviVoiceByPowerKey") ?: continue
            runCatching {
                intercept(
                    id = "vivo.powerkey.jovi",
                    executable = method,
                    description = "$className#startJoviVoiceByPowerKey"
                ) { chain ->
                    val launched = launch(chain.getThisObject(), "power-key-longpress", logger)
                    if (launched) {
                        logger.warn("电源键长按 → Eta")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                logger.warn("电源键拦截已挂载: $className")
            }.onFailure {
                logger.warn("电源键拦截挂载失败($className): ${it.javaClass.simpleName}")
            }
            return
        }
        missing(
            "vivo.powerkey.jovi",
            "VivoInputPolicy.startJoviVoiceByPowerKey",
            "未找到电源键 Jovi 入口，长按仍归小 V"
        )
    }

    /**
     * 按已知签名逐个查找 startServiceLocked / startServiceInnerLocked。
     *
     * Android 15 与 vivo 定制在参数个数上有差异，这里从全到简依次尝试；
     * 每次都走 [HookSupport.findMethod]（内部 catch LinkageError），
     * 不会因为某个 @hide 参数类型加载失败而击穿 system_server。
     */
    private fun findStartServiceMethods(activeServices: Class<*>): List<Method> {
        val iat = runCatching {
            Class.forName("android.app.IApplicationThread", false, activeServices.classLoader)
        }.getOrNull()
        val int = java.lang.Integer.TYPE
        val bool = java.lang.Boolean.TYPE
        val intent = Intent::class.java
        val string = String::class.java
        val signatures = buildList<List<Class<*>>> {
            if (iat != null) {
                add(listOf(iat, intent, string, int, int, bool, string, int, bool, int))
                add(listOf(iat, intent, string, int, int, bool, string, int, bool))
                add(listOf(iat, intent, string, int, int, bool, string, int))
                add(listOf(iat, intent, string, int, int, bool, string, int, int))
                add(listOf(iat, intent, string, int, int, string, int))
            }
            add(listOf(intent, string, int, int, string, int))
        }
        return buildList {
            for (name in listOf("startServiceLocked", "startServiceInnerLocked")) {
                for (signature in signatures) {
                    val found = HookSupport.findMethod(activeServices, name, *signature.toTypedArray()) ?: continue
                    if (none { it.name == found.name && it.parameterCount == found.parameterCount }) {
                        add(found)
                    }
                }
            }
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
            logger.warn("唤醒拦截: $action → MainActivity")
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
