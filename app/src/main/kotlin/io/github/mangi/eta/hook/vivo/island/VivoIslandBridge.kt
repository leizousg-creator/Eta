package io.github.mangi.eta.hook.vivo.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.hook.support.HookSupport

/**
 * vivo 原子岛的进程内桥接（`com.vivo.systemuiplugin:island` 进程）。
 *
 * 为什么必须走这里：Eta 主进程往通知 extras 里写 superx 字段的路，只在系统应用上有效——
 * 实测第三方应用的 `notification.superx.island` / `capsule` 子 Bundle 在 NMS 侧就被剥掉，
 * 只留下 `displays`、`islandNotify` 和一个重建出来的 `SUPERX_VALUE`，插件读到
 * `capsule.state = 0` 后按「删除岛」处理，所以主进程那条路永远出不来岛。
 *
 * 岛自己在这个进程里有完整编程接口（实测可用）：
 *
 *  - `AtomIslandsDataReceiver.notifyAtomIsland(bean)` / `deleteAtomIsland(id)`
 *  - `AtomIslandsBean$Builder`：buildId / buildSourcePackage / buildLeftTemplate /
 *    buildRightTemplate / buildLeftContent / buildRightContent / buildLeftIcon /
 *    buildShowTime / buildForceShow / buildClickIslandResp / buildIslandClick ……
 *  - `AtomIslandsDataController.clickNotificationWithDelayUpdate`（点击入口）
 *
 * 本类接收 Eta 主进程发来的广播，构造 bean 投递到岛。
 * 岛 API 只在当前进程可用，所以主进程只能通过广播跨进程触发。
 *
 * 反射纪律：ROM 类探测会抛 `NoClassDefFoundError`（Error 而非 Exception），
 * 所有反射调用都包在 runCatching 内，绝不允许击穿 systemuiplugin 进程。
 */
internal object VivoIslandBridge {

    private const val ISLAND_SERVICE = "com.vivo.island.IslandService"
    private const val PREFIX = "com.vivo.island."
    private const val ETA_PACKAGE = "io.github.mangi.eta"

    /** Eta 主进程 → 本进程。 */
    const val ACTION_SHOW = "io.github.mangi.eta.island.SHOW"
    const val ACTION_REMOVE = "io.github.mangi.eta.island.REMOVE"

    private const val EXTRA_ID = "id"
    private const val EXTRA_CONTENT = "content"
    private const val EXTRA_CLASS = "className"
    private const val EXTRA_SHOW_SECONDS = "showSeconds"

    private const val MAIN_ACTIVITY = "io.github.mangi.eta.ui.MainActivity"

    /** 岛进程的 Context，由 IslandService.onCreate 填入；点击回调需要它启动 Eta。 */
    @Volatile
    private var islandContext: android.content.Context? = null

    /** 探测阶段用；确认可用后测试期也可以人工触发一次。 */
    private val PROBE_CLASSES = listOf(
        "IslandService",
        "data.AtomIslandsDataReceiver",
        "data.AtomIslandsDataController",
        "data.AtomIslandDisplayController",
        "data.NewSuperXLoader",
        "data.AtomIslandsBean\$Builder",
        "utils.IslandUtils",
        "utils.IslandNotifyHelper",
        "view.CapsuleContentView",
    )

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "VivoIsland")
        val logger = hooks.logger

        return hooks.install {
            probe(classLoader, logger)

            // 点击接管：岛自己点开详情/单击时都会经过 clickNotificationWithDelayUpdate(key)。
            // 只认 Eta 自己的通知 key，其余原样放行。
            val dataController = HookSupport.findClassOrNull(
                classLoader,
                "${PREFIX}data.AtomIslandsDataController",
            )
            val clickMethod = dataController?.let {
                HookSupport.findMethod(it, "clickNotificationWithDelayUpdate", String::class.java)
            }
            if (clickMethod == null) {
                missing(
                    "vivo.island.click",
                    "$PREFIX data.AtomIslandsDataController#clickNotificationWithDelayUpdate",
                    "未找到岛点击入口，岛条点击仍走原逻辑",
                )
            } else {
                intercept(
                    id = "vivo.island.click",
                    executable = clickMethod,
                    description = "AtomIslandsDataController.clickNotificationWithDelayUpdate",
                ) { chain ->
                    val key = chain.getArg(0) as? String
                    if (key == null || !key.contains(ETA_PACKAGE)) {
                        return@intercept chain.proceed()
                    }
                    val context = islandContext
                    if (context == null) {
                        return@intercept chain.proceed()
                    }
                    val started = runCatching {
                        context.startActivity(
                            Intent()
                                .setClassName(ETA_PACKAGE, MAIN_ACTIVITY)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                        true
                    }.getOrDefault(false)
                    if (started) {
                        logger.debug { "island 点击 → Eta (key=$key)" }
                        null
                    } else {
                        chain.proceed()
                    }
                }
            }

            val serviceClass = HookSupport.findClassOrNull(classLoader, ISLAND_SERVICE)
            if (serviceClass == null) {
                missing("vivo.island.service", ISLAND_SERVICE, "未找到岛服务，跳过桥接")
                return@install
            }
            val onCreate = HookSupport.findMethod(serviceClass, "onCreate")
            if (onCreate == null) {
                missing("vivo.island.onCreate", "$ISLAND_SERVICE#onCreate", "未找到 onCreate，跳过桥接")
                return@install
            }
            intercept(
                id = "vivo.island.service",
                executable = onCreate,
                description = "IslandService.onCreate 挂载岛投递",
            ) { chain ->
                val result = chain.proceed()
                runCatching {
                    val service = chain.getThisObject()
                    val context = (service as? android.app.Service)?.applicationContext
                    if (context == null) {
                        logger.warn("island 桥接：拿不到 Service Context，跳过投递")
                    } else {
                        islandContext = context
                        registerReceiver(context, logger)
                    }
                }.onFailure { logger.warn("island 桥接挂载失败: ${it.javaClass.simpleName}") }
                result
            }
        }
    }

    private fun registerReceiver(context: Context, logger: ModuleLogger) {
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_REMOVE)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                runCatching { handle(ctx ?: context, intent, logger) }
                    .onFailure { logger.warn("island 投递失败: ${it.javaClass.simpleName}") }
            }
        }
        // 跨 uid 广播：Android 13 起必须显式声明导出标志，否则收不到。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        logger.debug { "island 投递通道已就绪: $ACTION_SHOW" }
    }

    private fun handle(context: Context, intent: Intent, logger: ModuleLogger) {
        val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: return
        if (intent.action == ACTION_REMOVE) {
            deleteAtomIsland(context, id, logger)
            return
        }
        val content = intent.getStringExtra(EXTRA_CONTENT)?.takeIf { it.isNotBlank() } ?: return
        val className = intent.getStringExtra(EXTRA_CLASS)
        val seconds = intent.getIntExtra(EXTRA_SHOW_SECONDS, DEFAULT_SHOW_SECONDS)
        notifyAtomIsland(context, id, content, className, seconds, logger)
    }

    /**
     * 构造 [AtomIslandsBean] 并投递。
     * 每个 buildXxx 单独 runCatching：某个字段在当前 ROM 缺失时只丢那一项，
     * 其余字段照常投递，不会整条失败。
     */
    private fun notifyAtomIsland(
        context: Context,
        id: String,
        content: String,
        className: String?,
        seconds: Int,
        logger: ModuleLogger,
    ) {
        val loader = context.classLoader
        val builderClass = Class.forName("$PREFIX" + "data.AtomIslandsBean\$Builder", false, loader)
        val builder = builderClass.getDeclaredConstructor().newInstance()

        fun field(name: String, type: Class<*>, value: Any?) {
            runCatching { builderClass.getMethod(name, type).invoke(builder, value) }
                .onFailure { logger.warn("island 字段 $name 不可用: ${it.javaClass.simpleName}") }
        }

        field("buildId", String::class.java, id)
        field("buildSourcePackage", String::class.java, ETA_PACKAGE)
        // 第三方应用在插件里走的是 BUSINESS_NEW_SUPER_X（见 SuperXCapsuleDataController
        // 的新建态分支），不设 businessKey 时岛内部的列表/视图层会取到 null。
        field("buildBusinessKey", String::class.java, "BUSINESS_NEW_SUPER_X")
        field("buildAtomIslandsType", Integer.TYPE, 2)
        field("buildPriority", Integer.TYPE, 2)
        field("buildLandingInfoPkn", String::class.java, ETA_PACKAGE)
        field("buildLandingInfoPkt", String::class.java, MAIN_ACTIVITY)
        field("buildLandingInfoIsDeepLink", java.lang.Boolean.TYPE, false)
        field("buildLeftTemplate", Integer.TYPE, 1)
        field("buildRightTemplate", Integer.TYPE, 4)
        field("buildLeftContent", CharSequence::class.java, "Eta")
        field("buildRightContent", CharSequence::class.java, content.take(40))
        field("buildShowTime", Integer.TYPE, seconds.coerceIn(3, 30))
        // notifyAtomIsland 第一件事就是 buildTimestamp + build()，然后
        // 「timeout < now 直接 return」——不设 timeout 等于投递被静默丢弃。
        field(
            "buildTimeout",
            java.lang.Long.TYPE,
            System.currentTimeMillis() + seconds.coerceIn(3, 30) * 1000L,
        )
        field("buildForceShow", java.lang.Boolean.TYPE, true)
        field("buildSupportNewNode", java.lang.Boolean.TYPE, true)
        // 1 = 允许点击。不设这一项时岛条不响应点击（实测：点了没有任何反应），
        // 即使 buildClickIslandResp 已经给了 PendingIntent 也不会被触发。
        field("buildIslandClick", Integer.TYPE, 1)

        runCatching {
            val iconRes = context.packageManager.getApplicationInfo(ETA_PACKAGE, 0).icon
            builderClass.getMethod("buildLeftIcon", Icon::class.java)
                .invoke(builder, Icon.createWithResource(ETA_PACKAGE, iconRes))
        }.onFailure { logger.warn("island 图标装载失败: ${it.javaClass.simpleName}") }

        if (className != null) {
            runCatching {
                val open = Intent().setClassName(ETA_PACKAGE, className)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pending = android.app.PendingIntent.getActivity(
                    context,
                    3,
                    open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                builderClass.getMethod("buildClickIslandResp", android.app.PendingIntent::class.java)
                    .invoke(builder, pending)
            }.onFailure { logger.warn("island 点击响应装载失败: ${it.javaClass.simpleName}") }
        }

        // 注意签名：notifyAtomIsland(AtomIslandsBean.Builder)，不是 Bean。
        // 传 Bean 会抛 NoSuchMethodException，被外层 runCatching 吞成「投递失败」。
        val receiverClass = Class.forName("$PREFIX" + "data.AtomIslandsDataReceiver", false, loader)
        val receiver = receiverClass.getMethod("getInstance").invoke(null)
        runCatching {
            receiverClass.getMethod("notifyAtomIsland", builderClass).invoke(receiver, builder)
        }.onSuccess {
            logger.debug { "island 已投递: $id → $content" }
        }.onFailure {
            // InvocationTargetException 的 message 总是 null，真正原因在 cause 里。
            val cause = it.cause ?: it
            logger.warn("island 投递失败: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun deleteAtomIsland(context: Context, id: String, logger: ModuleLogger) {
        val receiverClass = Class.forName("$PREFIX" + "data.AtomIslandsDataReceiver", false, context.classLoader)
        val receiver = receiverClass.getMethod("getInstance").invoke(null)
        receiverClass.getMethod("deleteAtomIsland", String::class.java).invoke(receiver, id)
        logger.debug { "island 已移除: $id" }
    }

    /**
     * 逐类探测：先只记「类在不在 + 方法名清单」。
     * 不碰 `parameterTypes`——那也是反射解析，同样可能抛 LinkageError。
     */
    private fun probe(classLoader: ClassLoader, logger: ModuleLogger) {
        var present = 0
        PROBE_CLASSES.forEach { simpleName ->
            val fullName = PREFIX + simpleName
            val clazz = runCatching { HookSupport.findClassOrNull(classLoader, fullName) }.getOrNull()
            if (clazz == null) {
                logger.warn("island.probe 缺类: $simpleName")
                return@forEach
            }
            present++
            val methodNames = runCatching {
                clazz.declaredMethods.map { it.name }.distinct().sorted()
            }.getOrElse { emptyList() }
            logger.debug { "island.probe 类存在: $simpleName (${methodNames.size} methods)" }
        }
        logger.warn("island.probe 完成: $present/${PROBE_CLASSES.size} 个类可访问")
    }

    private const val DEFAULT_SHOW_SECONDS = 10
}
