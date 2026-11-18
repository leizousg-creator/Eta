package io.github.mangi.eta.hook.vivo.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger

import io.github.libxposed.api.XposedModule

/**
 * vivo 原子岛的进程内桥接（`com.vivo.systemuiplugin:island` 进程）。
 *
 * Eta 现有的 [io.github.mangi.eta.agent.island.VivoCapsuleExtras] 走的是「往通知 extras 里
 * 写 superx 字段，让系统按固定模板渲染」的路，只能出胶囊 + 一行文字。
 * 岛自己在这个进程里有完整编程接口（探测结果 9/9 类可访问）：
 *
 *  - `AtomIslandsDataReceiver.notifyAtomIsland(bean)` / `deleteAtomIsland(id)`
 *  - `AtomIslandsBean$Builder`：buildId / buildSourcePackage / buildLeftTemplate /
 *    buildRightTemplate / buildLeftContent / buildRightContent / buildLeftIcon /
 *    buildShowTime / buildForceShow / buildClickIslandResp / buildTimeout ……
 *  - `NewSuperXLoader.generateCardView`（留作下一版自定义卡片）
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
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "VivoIsland")
        val logger = hooks.logger

        return hooks.install {
            probe(classLoader, logger)
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
                description = "IslandService.onCreate 挂载岛投递"
            ) { chain ->
                val result = chain.proceed()
                runCatching {
                    val service = chain.getThisObject()
                    val context = (service as? android.app.Service)?.applicationContext
                    if (context == null) {
                        logger.warn("island 桥接：拿不到 Service Context，跳过投递")
                    } else {
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
        logger.warn("island 投递通道已就绪: ${ACTION_SHOW}")
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
        logger: ModuleLogger
    ) {
        val loader = context.classLoader
        val builderClass = Class.forName("$PREFIX.data.AtomIslandsBean\$Builder", false, loader)
        val builder = builderClass.getDeclaredConstructor().newInstance()

        fun field(name: String, type: Class<*>, value: Any?) {
            runCatching { builderClass.getMethod(name, type).invoke(builder, value) }
                .onFailure { logger.warn("island 字段 $name 不可用: ${it.javaClass.simpleName}") }
        }

        field("buildId", String::class.java, id)
        field("buildSourcePackage", String::class.java, ETA_PACKAGE)
        field("buildLeftTemplate", Integer.TYPE, 1)
        field("buildRightTemplate", Integer.TYPE, 4)
        field("buildLeftContent", CharSequence::class.java, "Eta")
        field("buildRightContent", CharSequence::class.java, content.take(40))
        field("buildShowTime", Integer.TYPE, seconds.coerceIn(3, 30))
        field("buildForceShow", java.lang.Boolean.TYPE, true)
        field("buildSupportNewNode", java.lang.Boolean.TYPE, true)

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
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                builderClass.getMethod("buildClickIslandResp", android.app.PendingIntent::class.java)
                    .invoke(builder, pending)
            }.onFailure { logger.warn("island 点击响应装载失败: ${it.javaClass.simpleName}") }
        }

        val bean = builderClass.getMethod("build").invoke(builder)
        val receiverClass = Class.forName("$PREFIX.data.AtomIslandsDataReceiver", false, loader)
        val receiver = receiverClass.getMethod("getInstance").invoke(null)
        receiverClass.getMethod("notifyAtomIsland", Class.forName("$PREFIX.data.AtomIslandsBean", false, loader))
            .invoke(receiver, bean)
        logger.warn("island 已投递: $id → $content")
    }

    private fun deleteAtomIsland(context: Context, id: String, logger: ModuleLogger) {
        val receiverClass = Class.forName("$PREFIX.data.AtomIslandsDataReceiver", false, context.classLoader)
        val receiver = receiverClass.getMethod("getInstance").invoke(null)
        receiverClass.getMethod("deleteAtomIsland", String::class.java).invoke(receiver, id)
        logger.warn("island 已移除: $id")
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
            logger.warn("island.probe 类存在: $simpleName (${methodNames.size} methods)")
        }
        logger.warn("island.probe 完成: $present/${PROBE_CLASSES.size} 个类可访问")
    }

    private const val DEFAULT_SHOW_SECONDS = 10
}
