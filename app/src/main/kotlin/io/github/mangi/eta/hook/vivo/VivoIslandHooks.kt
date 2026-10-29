package io.github.mangi.eta.hook.vivo

import android.service.notification.StatusBarNotification
import android.graphics.drawable.Icon
import android.os.Bundle
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.libxposed.api.XposedModule

/**
 * 在 SystemUI 进程里把 Eta 的通知直接投递成 vivo 原子岛卡片。
 *
 * 背景：系统插件 `SuperXCapsuleDataController.addAtomIslandCapsuleElementToShow` 只在
 * 「不支持原子岛」时才解析 `notification.superx.capsule`；支持原子岛的设备会改走
 * `notification.superx.island` 的新协议。为了不追厂商的新协议，这里在方法入口自行判断
 * 包名，命中 Eta 时用插件自己的 bean / 代理把卡片投出去（等价于它 legacy 分支做的事）。
 */
internal object VivoIslandHooks {
    private const val TARGET_PACKAGE = "io.github.mangi.eta"
    private const val CAPSULE_BUNDLE = "notification.superx.capsule"
    private const val KEY_STATE = "notification.superx.capsule.state"
    private const val KEY_CONTENT = "notification.superx.capsule.content"
    private const val KEY_ICON = "notification.superx.capsule.icon"
    private const val KEY_SHOW_TIME = "notification.superx.capsule.showTime"

    private const val CONTROLLER_CLASS =
        "com.vivo.systemuiplugin.superx.statusbar.SuperXCapsuleDataController"
    private const val BEAN_CLASS = "com.vivo.island.bean.IslandDataBean"
    private const val LEFT_CLASS = "com.vivo.island.bean.LeftIslandBean"
    private const val RIGHT_CLASS = "com.vivo.island.bean.RightIslandBean"
    private const val TYPE_INFO_CLASS = "com.vivo.island.info.TypeInfo"
    private const val PROXY_CLASS = "com.vivo.systemuiplugin.systemui.islands.IslandServiceProxy"
    private const val LANDING_JSON = "{\"mType\":\"app\",\"mPkgName\":\"" + TARGET_PACKAGE + "\",\"isDeepLink\":false}"

    /** 内容没变化就不重复投递，避免进度刷新时胶囊闪烁。 */
    @Volatile
    private var lastSignature: String? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "VivoIsland")
        val logger = hooks.logger
        return hooks.install {
            val controller = HookSupport.findClassOrNull(classLoader, CONTROLLER_CLASS)
            val method = controller?.let {
                HookSupport.findMethod(it, "addAtomIslandCapsuleElementToShow", StatusBarNotification::class.java)
            }
            if (method == null) {
                missing("vivo.island.capsule", "SuperXCapsuleDataController.addAtomIslandCapsuleElementToShow", "未找到原子岛入口")
                return@install
            }
            val clickClass = HookSupport.findClassOrNull(classLoader, "com.vivo.island.data.AtomIslandsDataController")
            val clickMethod = clickClass?.let { HookSupport.findMethod(it, "handleIslandClickEvent", Int::class.javaPrimitiveType!!) }
            if (clickMethod != null) {
                intercept("vivo.island.click", clickMethod, "记录岛的点击事件") { chain ->
                    logger.info("岛点击事件: ${chain.args.getOrNull(0)}")
                    chain.proceed()
                }
            } else {
                logger.warn("未找到 AtomIslandsDataController.handleIslandClickEvent，点击无法观测")
            }

            intercept("vivo.island.capsule", method, "Eta 通知投递到原子岛") { chain ->
                runCatching {
                    val sbn = chain.args.getOrNull(0) as? StatusBarNotification
                    if (sbn != null) post(sbn, classLoader, logger)
                }.onFailure {
                    logger.warn("原子岛投递失败: ${it.javaClass.simpleName} ${it.message}")
                }
                chain.proceed()
            }
        }
    }

    private fun post(
        sbn: StatusBarNotification,
        loader: ClassLoader,
        logger: ModuleLogger,
    ) {
        if (sbn.packageName != TARGET_PACKAGE) return
        val extras = sbn.notification?.extras ?: return
        val capsule = extras.getBundle(CAPSULE_BUNDLE) ?: return

        val state = capsule.getInt(KEY_STATE, 0)
        val key = sbn.key ?: return
        val content = capsule.getString(KEY_CONTENT)?.takeIf { it.isNotBlank() }
        if (state != 0) {
            val signature = "$content|${capsule.getInt(KEY_SHOW_TIME, -1)}"
            if (signature == lastSignature) return
            lastSignature = signature
        } else {
            lastSignature = null
        }
        val showTime = capsule.getInt(KEY_SHOW_TIME, -1).let { if (it <= 0) 8 else it }

        val beanClass = Class.forName(BEAN_CLASS, false, loader)
        val bean = beanClass.getMethod("createDefaultModel").invoke(null)
        bean.setInt("setOperation", if (state == 0) 2 else 0)
        bean.setString("setId", key)
        bean.invoke("setSbn", StatusBarNotification::class.java, sbn)
        // 点击处理：AtomIslandBaseLinearLayout 里只有 getIslandClick()==1 时才走
        // 「handleIslandClickEvent + getClickIslandResp()」，所以两个都要给。
        sbn.notification?.contentIntent?.let { pending ->
            bean.invoke("setClickIslandResp", android.app.PendingIntent::class.java, pending)
            bean.setInt("setIslandClick", 1)
        }
        bean.setString("setSourcePackageName", sbn.packageName)
        bean.setBool("setScrollRemove", true)
        bean.setBool("setSupportAppPkgDisappear", true)

        if (state != 0) {
            val icon = capsule.getParcelable(KEY_ICON, Icon::class.java)
            if (content == null || icon == null) {
                logger.warn("原子岛字段缺失: content=${content != null} icon=${icon != null}")
                return
            }
            val typeInfo = Class.forName(TYPE_INFO_CLASS, false, loader)
            val businessKey = typeInfo.getField("BUSINESS_NEW_SUPER_X").get(null) as String
            bean.setString("setBusinessKey", businessKey)
            // 点击胶囊回到 Eta（landing 是插件识别点击目标的字段）。
            bean.setString("setLandingInfo", LANDING_JSON)
            bean.invoke("setTimeout", Long::class.javaPrimitiveType!!, System.currentTimeMillis() + showTime * 1000L)
            bean.setInt("setShowTime", showTime)
            bean.setInt("setPriority", 2)
            bean.setInt("setAtomIslandsType", 2)
            bean.setInt("setNewNode", 0)

            val left = Class.forName(LEFT_CLASS, false, loader)
                .getMethod("createLeftIslandBeanModel").invoke(null)
            left.setInt("setLeftTemplate", 3)
            left.invoke("setLeftIcon", Icon::class.java, icon)

            val right = Class.forName(RIGHT_CLASS, false, loader)
                .getMethod("createRightIslandBeanModel").invoke(null)
            right.setInt("setRightTemplate", 7)
            right.setString("setRightContent", content)

            bean.invoke("setLeftIsland", Class.forName(LEFT_CLASS, false, loader), left)
            bean.invoke("setRightIsland", Class.forName(RIGHT_CLASS, false, loader), right)
        }

        val proxyClass = Class.forName(PROXY_CLASS, false, loader)
        val proxy = proxyClass.getField("INSTANCE").get(null)
        proxyClass.getMethod("postIslandDataBean", beanClass).invoke(proxy, bean)
        logger.info("原子岛已投递: state=$state key=$key showTime=$showTime")
    }

    private fun Any.invoke(name: String, type: Class<*>, value: Any?) {
        javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(type) }
            ?.invoke(this, value)
            ?: javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 }
                ?.invoke(this, value)
    }

    private fun Any.setInt(name: String, value: Int) {
        javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
            ?.invoke(this, value)
    }

    private fun Any.setString(name: String, value: String) {
        javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
            ?.invoke(this, value)
    }

    private fun Any.setBool(name: String, value: Boolean) {
        javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType }
            ?.invoke(this, value)
    }
}
