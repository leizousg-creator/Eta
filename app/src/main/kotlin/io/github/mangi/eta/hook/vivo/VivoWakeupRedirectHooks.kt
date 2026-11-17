package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger

import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedModule

/**
 * 把小 V 的唤醒入口重定向到 Eta。
 *
 * 原 ROM 里所有唤醒动作最终都会落到 `WakeupService.onStartCommand` / `onBind`：
 * 上滑白条、长按电源键、语音热词、圈搜、外接键盘快捷键等。这里在动作进入小 V 之前拦下，
 * 直接把 [EtaVoiceAssistActivity] 拉起来，小 V 自身保持不被唤起。
 *
 * 与 EtaRedirect 的实现差异：不依赖老 Xposed 的 `setResult`，失败时原样走 ROM 逻辑，
 * 保证注入不完整或 Activity 启动受限时不会让侧键/白条彻底失效。
 */
internal object VivoWakeupRedirectHooks {

    private const val WAKEUP_SERVICE = "com.vivo.ai.copilot.framework.wakeup.WakeupService"
    private const val ETA_PACKAGE = "io.github.mangi.eta"
    private const val ETA_VOICE_ACTIVITY = "$ETA_PACKAGE.agent.voice.EtaVoiceAssistActivity"

    /** 上滑/Home 键唤出白条，action 后面可能带额外后缀，按前缀匹配。 */
    private const val WHITE_STRIP_PREFIX = "com.vivo.action.copilot.SHOW_WHITE_STRIP_BY_HOMEKEY"

    /** 其余需要重定向的唤醒动作，取自 ROM WakeupService 实际收到的 action 集合。 */
    private val EXACT_ACTIONS = setOf(
        "com.vivo.ai.copilot.action.START_CIRCLE2SEARCH",
        "com.vivo.intent.action.WAKEUP_AGENT_BY_POWER",
        "com.vivo.intent.action.WAKEUP_AGENT_BY_POWER_CHARGE_START",
        "com.vivo.intent.action_WAKE_UP_AGENT",
        "com.vivo.ai.copilot.action.ACTION_SYSTEM_BAR_CALL_PANEL",
        "com.vivo.intent.action.WAKEUP_COPILOT_BY_KEYBOARD"
    )

    /** 同一秒内多处触发时只启动一次，避免白条上滑时连续弹两个界面。 */
    private const val LAUNCH_GRACE_MS = 1200L

    @Volatile
    private var lastLaunchAt = 0L

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "VivoWakeup")
        val logger = hooks.logger

        return hooks.install {
            val serviceClass = HookSupport.findClassOrNull(classLoader, WAKEUP_SERVICE)
            if (serviceClass == null) {
                missing("vivo.wakeup.service", WAKEUP_SERVICE, "未找到小 V 唤醒服务，跳过唤醒重定向")
                return@install
            }

            val onStartCommand = HookSupport.findMethod(
                serviceClass,
                "onStartCommand",
                Intent::class.java,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE
            )
            if (onStartCommand == null) {
                missing(
                    "vivo.wakeup.start",
                    "$WAKEUP_SERVICE#onStartCommand",
                    "未找到唤醒服务 onStartCommand，跳过唤醒重定向"
                )
            } else {
                intercept(
                    id = "vivo.wakeup.start",
                    executable = onStartCommand,
                    description = "WakeupService.onStartCommand 唤醒重定向"
                ) { chain ->
                    val intent = chain.getArg(0) as? Intent
                    if (!shouldRedirect(intent?.action)) {
                        return@intercept chain.proceed()
                    }
                    val launched = launch(chain.getThisObject() as? Context, intent?.action, logger)
                    // 启动成功就把小 V 的这次唤醒吞掉（START_NOT_STICKY），失败则交还原逻辑。
                    if (launched) 2 else chain.proceed()
                }
            }

            val onBind = HookSupport.findMethod(serviceClass, "onBind", Intent::class.java)
            if (onBind == null) {
                missing(
                    "vivo.wakeup.bind",
                    "$WAKEUP_SERVICE#onBind",
                    "未找到唤醒服务 onBind，白条 bind 路径不重定向"
                )
            } else {
                intercept(
                    id = "vivo.wakeup.bind",
                    executable = onBind,
                    description = "WakeupService.onBind 白条唤醒重定向"
                ) { chain ->
                    val intent = chain.getArg(0) as? Intent
                    val action = intent?.action
                    if (action == null || !action.startsWith(WHITE_STRIP_PREFIX)) {
                        return@intercept chain.proceed()
                    }
                    val launched = launch(chain.getThisObject() as? Context, action, logger)
                    if (launched) null else chain.proceed()
                }
            }
        }
    }

    private fun shouldRedirect(action: String?): Boolean {
        if (action == null) return false
        if (action.startsWith(WHITE_STRIP_PREFIX)) return true
        return action in EXACT_ACTIONS
    }

    /**
     * 拉起 Eta 助手界面。启动失败必须返回 false，让调用方回退到小 V 原生行为，
     * 不能把一个失败的重定向表现成"按键无响应"。
     */
    private fun launch(context: Context?, action: String?, logger: ModuleLogger): Boolean {
        if (context == null) return false
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < LAUNCH_GRACE_MS) return true
        lastLaunchAt = now

        val intent = Intent().apply {
            setClassName(ETA_PACKAGE, ETA_VOICE_ACTIVITY)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        val started = runCatching { context.startActivity(intent) }
            .onFailure { logger.warn("唤醒重定向启动 Eta 失败: action=$action, type=${it.javaClass.simpleName}") }
            .isSuccess
        if (started) {
            logger.debug { "唤醒重定向: $action -> EtaVoiceAssistActivity" }
        }
        return started
    }
}
