package io.github.mangi.eta.hook.vivo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.hook.support.HookSupport

/**
 * 用 vivo 小 V 的身份发超级岛通知（`com.vivo.ai.copilot` 进程内）。
 *
 * 为什么必须借这个包：Eta 自己往通知 extras 里写 superx 字段会被 NMS 白名单剥掉
 * （实测只剩 `displays` / `islandNotify` / 重建出来的 `SUPERX_VALUE`），
 * 而岛进程那条桥接虽然能把数据塞进 `mAtomIslandReceivers`，
 * 但 `IslandServiceListener` 是 null——SystemUI 不认这条需求就不会 bind 岛服务，
 * 结果屏幕上什么都不画。系统应用发的通知才会被完整放行。
 *
 * 字段集照抄旧 fork 里跑通过的 [VivoIslandNotifications]：
 * 顶层 template=4 / displays=0x111 / scene=FAST_COMMAND，
 * island 子 Bundle 用 leftTemplate=1 + rightTemplate=6、showTime=-1（常驻，
 * 由主进程心跳续期）。
 */
internal object VivoIslandNotifyBridge {

    const val ACTION_SHOW = "io.github.mangi.eta.island.SHOW"
    const val ACTION_HIDE = "io.github.mangi.eta.island.REMOVE"
    private const val EXTRA_CONTENT = "content"
    private const val EXTRA_SECONDS = "showSeconds"

    private const val ETA_PACKAGE = "io.github.mangi.eta"
    private const val MAIN_ACTIVITY = "io.github.mangi.eta.ui.MainActivity"
    private const val CHANNEL = "SI_NOTIFICATION_CHANNEL_DEFAULT"
    private const val NOTIFICATION_ID = 0x455441
    private const val TAG = "eta:vivo:island"
    private const val SCENE = "FAST_COMMAND"
    private const val TITLE = "Eta"

    private const val ACTION_DISMISS = "io.github.mangi.eta.vivo.action.DISMISS_ISLAND_TASK"
    private const val EXTRA_TOKEN = "eta_vivo_notification_token"
    private const val TOKEN = "eta-vivo-island"

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "VivoCopilot")
        val logger = hooks.logger
        return hooks.install {
            val app = HookSupport.findClassOrNull(classLoader, "com.vivo.ai.copilot.CopilotApp")
            val onCreate = app?.let { HookSupport.findMethod(it, "onCreate") }
            if (onCreate == null) {
                missing(
                    "vivo.copilot.bootstrap",
                    "com.vivo.ai.copilot.CopilotApp.onCreate",
                    "未找到小 V Application 入口，超级岛无法借道发通知",
                )
                return@install
            }
            intercept(
                id = "vivo.copilot.bootstrap",
                executable = onCreate,
                description = "Vivo CopilotApp.onCreate 挂载超级岛通知",
            ) { chain ->
                val result = chain.proceed()
                runCatching {
                    val context = chain.getThisObject() as? Context
                    if (context == null) {
                        logger.warn("island 通知桥：拿不到小 V Context，跳过")
                    } else {
                        registerReceiver(context.applicationContext, logger)
                    }
                }.onFailure { logger.warn("island 通知桥挂载失败: ${it.javaClass.simpleName}") }
                result
            }
        }
    }

    private fun registerReceiver(context: Context, logger: ModuleLogger) {
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_HIDE)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                runCatching {
                    when (intent.action) {
                        ACTION_SHOW -> {
                            val content = intent.getStringExtra(EXTRA_CONTENT)?.takeIf { it.isNotBlank() }
                            if (content != null) {
                                post(context, content, intent.getIntExtra(EXTRA_SECONDS, 10))
                            }
                        }
                        ACTION_HIDE -> cancel(context)
                    }
                }.onFailure { logger.warn("island 通知投递失败: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        logger.warn("island 通知通道已就绪（借小 V 身份）")
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Eta", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    private fun iconFor(context: Context): Icon {
        // 只能用小 V 自己的资源：跨包按 Eta 的资源 id 取图在系统进程里不一定解得开。
        val resId = context.resources.getIdentifier(
            "vivo_push_ard13_notifyicon",
            "drawable",
            context.packageName,
        ).takeIf { it != 0 } ?: context.applicationInfo.icon
        return Icon.createWithResource(context.packageName, resId)
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        3,
        Intent().setClassName(ETA_PACKAGE, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(context: Context, content: String, @Suppress("UNUSED_PARAMETER") seconds: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        val icon = iconFor(context)
        val open = openIntent(context)
        val detail = content.take(24)

        val base = Bundle().apply {
            putParcelable("notification.superx.baseInfos.icon", icon)
            putCharSequence("notification.superx.baseInfos.title", TITLE)
            putCharSequence("notification.superx.baseInfos.content", detail)
            putInt("notification.superx.baseInfos.generatingStatus", 1)
            putInt("notification.superx.baseInfos.subInfo", 2)
        }
        val island = Bundle().apply {
            putInt("island.superx.leftTemplate", 1)
            putInt("island.superx.rightTemplate", 6)
            putBoolean("island.superx.forceShow", true)
            putBoolean("island.superx.forceShowCard", false)
            putInt("island.superx.forceShowCardInt", 0)
            putInt("island.superx.islandClick", 0)
            putParcelable("island.superx.clickResp", open)
            // -1 = 常驻，由主进程心跳（6 秒）持续刷新；给正数会几秒后自己消失。
            putInt("island.superx.showTime", -1)
            putString("island.superx.callbackInfos", "eta:$TOKEN")
            putInt("island.superx.template", 4)
            putString(
                "island.superx.landingInfo",
                "{\"mType\":\"app\",\"deepLink\":false,\"mPkgName\":\"$ETA_PACKAGE\"}",
            )
            putBundle(
                "island.superx.leftInfo",
                Bundle().apply {
                    putParcelable("island.superx.leftInfo.icon", icon)
                    putCharSequence("island.superx.leftInfo.content", TITLE)
                    putInt("island.superx.leftInfo.generatingStatus", 1)
                },
            )
            putBundle(
                "island.superx.rightInfo",
                Bundle().apply {
                    putCharSequence("island.superx.rightInfo.capsuleContent", detail)
                    putInt("island.superx.rightInfo.capsuleBgColor", Color.rgb(62, 85, 190))
                },
            )
            putBundle("island.superx.baseInfos", base)
        }
        val extras = Bundle().apply {
            putString(EXTRA_TOKEN, TOKEN)
            putInt("notification.superx.operation", 1)
            putString("notification.superx.scene", SCENE)
            putInt("notification.superx.template", 4)
            putInt("notification.superx.displays", 0x111)
            putBoolean("notification.superx.islandNotify", false)
            putBoolean("notification.superx.sound", false)
            putBoolean("notification.superx.dismissWhenKill", true)
            putParcelable("notification.superx.clickResp", open)
            putBundle("notification.superx.baseInfos", base)
            putBundle("notification.superx.island", island)
            putBundle(
                "notification.superx.capsule",
                Bundle().apply {
                    putInt("notification.superx.capsule.state", 1)
                    putParcelable("notification.superx.capsule.icon", icon)
                    putString("notification.superx.capsule.content", TITLE)
                    putInt("notification.superx.capsule.showTime", -1)
                },
            )
            putBundle(
                "notification.superx.shortInfos",
                Bundle().apply {
                    putParcelable("notification.superx.shortInfos.icon", icon)
                    putCharSequence("notification.superx.shortInfos.coreInfoShort", detail)
                    putCharSequence("notification.superx.shortInfos.describeShort", TITLE)
                },
            )
        }

        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(TITLE)
            .setContentText(detail)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setExtras(extras)
            .build()
        manager.notify(TAG, NOTIFICATION_ID, notification)
    }

    private fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(TAG, NOTIFICATION_ID) }
        // OriginOS 会把超级岛通知改写成 VIVO_SUPERX_TAG，按 token 再扫一遍。
        runCatching {
            manager.activeNotifications
                .filter { it.notification.extras.getString(EXTRA_TOKEN) == TOKEN }
                .forEach { runCatching { manager.cancel(it.tag, it.id) } }
        }
    }
}
