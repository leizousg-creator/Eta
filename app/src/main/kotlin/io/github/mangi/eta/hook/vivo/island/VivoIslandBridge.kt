package io.github.mangi.eta.hook.vivo.island

import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger

import io.github.libxposed.api.XposedModule

/**
 * vivo 原子岛的进程内桥接（`com.vivo.systemuiplugin` 进程）。
 *
 * 背景：Eta 现有的 [io.github.mangi.eta.agent.island.VivoCapsuleExtras] 走的是
 * 「在通知 extras 里写 superx 字段，让系统按固定模板渲染」这条路，只能出胶囊 + 一行文字。
 * 岛自己在 `com.vivo.systemuiplugin` 进程里有一整套编程接口
 * （`AtomIslandsDataReceiver.notifyAtomIsland` / `NewSuperXLoader.generateCardView` /
 * `CapsuleContentView.setContentView`），可以塞自定义 View，做详情卡和点击响应。
 *
 * 本类第一步只做**能力探测**：把关键类与方法的真实名称打到日志里，
 * 确认这套 ROM 上的签名之后，再写具体的投递逻辑。
 * 探测全部包在 runCatching 内——ROM 类反射会抛 `NoClassDefFoundError`（Error），
 * 必须自己吞掉，不能让它击穿 systemuiplugin 进程。
 */
internal object VivoIslandBridge {

    private const val ISLAND_SERVICE = "com.vivo.island.IslandService"
    private const val PREFIX = "com.vivo.island."

    /** 按「能干什么」分组的关键类；名字来自 OriginOS消息岛 v0.1.22 的反射清单。 */
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
                description = "IslandService.onCreate 桥接点"
            ) { chain ->
                val result = chain.proceed()
                // onCreate 之后系统各控制器已初始化，此时再探一次能拿到更完整的信息。
                probe(classLoader, logger)
                result
            }
        }
    }

    /**
     * 逐类探测：先只记「类在不在 + 方法名清单」。
     * 不碰 `parameterTypes`——那也是反射解析，同样可能抛 LinkageError，
     * 等确认类结构后再单独取签名。
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
            }.getOrElse { error ->
                logger.warn("island.probe 方法枚举失败($simpleName): ${error.javaClass.simpleName}")
                emptyList()
            }
            logger.warn("island.probe 类存在: $simpleName (${methodNames.size} methods)")
            if (methodNames.isNotEmpty()) {
                logger.warn("island.probe $simpleName → ${methodNames.joinToString(", ")}")
            }
        }
        logger.warn("island.probe 完成: $present/${PROBE_CLASSES.size} 个类可访问")
    }
}
