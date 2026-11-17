package io.github.mangi.eta.hook.system

import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 原子岛（SuperX）通知白名单放行。
 *
 * 原 ROM 在 system_server 的 [VivoSuperXNotificationManager] 里校验通知发送方身份：
 * 只有持有 plugin 侧 serviceCode 注册凭证的应用（例如夸克的 quark_openToken）才被认作
 * 合法 SuperX 通知来源，第三方包即使把 `notification.superx.*` extras 全部写对，也会在
 * 这一层被整条丢掉（表现就是 extras 进了 Notification 但 ShowView 永远不出现）。
 *
 * 这里只对 Eta 自己的包名把校验结果置为 0（通过），其余应用完全走原逻辑，
 * 不改动小 V、夸克、系统应用自身的上岛行为。
 */
internal object SuperXValidatorHooks {

    private const val MANAGER_CLASS = "com.android.server.notification.VivoSuperXNotificationManager"
    private const val RECORD_CLASS = "com.android.server.notification.NotificationRecord"
    private const val VALIDATE_METHOD = "isValidSuperXNotification"
    private const val ETA_PACKAGE = "io.github.mangi.eta"

    /** 校验通过时 ROM 自身使用的返回值。 */
    private const val VALID = 0

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "SuperX")
        val logger = hooks.logger

        return hooks.install {
            val managerClass = HookSupport.findClassOrNull(classLoader, MANAGER_CLASS)
            if (managerClass == null) {
                missing("superx.manager", MANAGER_CLASS, "未找到 SuperX 通知管理器，跳过上岛放行")
                return@install
            }
            val recordClass = HookSupport.findClassOrNull(classLoader, RECORD_CLASS)
            if (recordClass == null) {
                missing("superx.record", RECORD_CLASS, "未找到通知记录类型，跳过上岛放行")
                return@install
            }
            val validate = HookSupport.findMethod(
                managerClass,
                VALIDATE_METHOD,
                recordClass,
                java.lang.Boolean.TYPE
            )
            if (validate == null) {
                missing(
                    "superx.validate",
                    "$MANAGER_CLASS#$VALIDATE_METHOD",
                    "未找到白名单校验方法，跳过上岛放行"
                )
                return@install
            }

            intercept(
                id = "superx.whitelist",
                executable = validate,
                description = "SuperX 白名单：放行 $ETA_PACKAGE"
            ) { chain ->
                val packageName = notificationPackageName(chain.args.firstOrNull())
                if (packageName == ETA_PACKAGE) {
                    logger.debug { "SuperX 白名单放行: $packageName" }
                    VALID
                } else {
                    chain.proceed()
                }
            }
        }
    }

    /**
     * 从 NotificationRecord 上取发送方包名。
     * ROM 侧链路是 record.getSbn().getPackageName()，任一步缺失都返回 null 走原逻辑。
     */
    private fun notificationPackageName(record: Any?): String? = runCatching {
        val sbn = invokeGetter(record, "getSbn") ?: return@runCatching null
        invokeGetter(sbn, "getPackageName") as? String
    }.getOrNull()

    private fun invokeGetter(target: Any?, name: String): Any? {
        if (target == null) return null
        val method: Method = HookSupport.findMethod(target.javaClass, name) ?: return null
        return method.invoke(target)
    }
}
