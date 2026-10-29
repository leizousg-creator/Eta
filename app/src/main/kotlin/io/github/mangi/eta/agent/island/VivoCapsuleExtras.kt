package io.github.mangi.eta.agent.island

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import io.github.mangi.eta.R

/**
 * 把 Eta 的进行中状态写进 vivo「原子岛 / 超级岛」能识别的通知扩展字段。
 *
 * 依据（反编译 SystemUI 插件 `com.vivo.systemuiplugin.superx.statusbar.SuperXCapsuleDataController`）：
 * 通知 extras 里带 `notification.superx.capsule` Bundle 时会被解析为岛卡片：
 *  - `notification.superx.capsule.state`   Int，1=显示，0=移除
 *  - `notification.superx.capsule.content` String，右侧文案
 *  - `notification.superx.capsule.icon`    Icon，左侧图标
 *  - `notification.superx.capsule.showTime` Int，停留秒数（-1 用系统默认）
 *  - `notification.superx.capsule.contentColor` / `bgColor` / `newNode` 可选
 *
 * 这里只写最小集合，且整段包在 runCatching 里：即使字段不被识别，也只是多发几条 extras，
 * 不影响 Eta 自己的普通通知。
 */
internal object VivoCapsuleExtras {
    private const val TAG = "EtaCapsule"
    private const val KEY_CAPSULE = "notification.superx.capsule"
    private const val KEY_STATE = "notification.superx.capsule.state"
    private const val KEY_CONTENT = "notification.superx.capsule.content"
    private const val KEY_ICON = "notification.superx.capsule.icon"
    private const val KEY_SHOW_TIME = "notification.superx.capsule.showTime"
    private const val KEY_CLICK_RESP = "notification.superx.capsule.clickResp"
    private const val KEY_LANDING_AUTO_HIDE = "notification.superx.capsule.landingAutoHide"
    private const val KEY_ISLAND = "notification.superx.island"

    /** 胶囊/岛卡片的停留秒数；运行期间每次通知更新都会续期。调整参数时只改这里。 */
    const val CAPSULE_SHOW_SECONDS = 8
    private const val LANDING_JSON =
        "{\"mType\":\"app\",\"mPkgName\":\"io.github.mangi.eta\",\"isDeepLink\":false}"

    fun apply(
        context: Context,
        builder: Notification.Builder,
        content: String,
        showSeconds: Int = CAPSULE_SHOW_SECONDS,
    ) {
        if (content.isBlank()) return
        runCatching {
            val icon = Icon.createWithResource(context, R.drawable.ic_notification)
            val open = PendingIntent.getActivity(
                context,
                2,
                Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val capsule = Bundle().apply {
                putInt(KEY_STATE, 1)
                putString(KEY_CONTENT, content.take(40))
                putParcelable(KEY_ICON, icon)
                putInt(KEY_SHOW_TIME, showSeconds.coerceIn(3, 30))
                // 点击胶囊直接回 Eta：插件会读这两个键决定“点击后做什么”。
                putParcelable(KEY_CLICK_RESP, open)
                putBoolean(KEY_LANDING_AUTO_HIDE, true)
            }
            builder.addExtras(
                Bundle().apply {
                    putBundle(KEY_CAPSULE, capsule)
                    // 新原子岛分支（SuperXCapsuleDataController.addAtomIslandElementToShow）
                    // 要求 leftTemplate/rightTemplate/leftInfo/rightInfo 齐全，
                    // 点击响应放在 island.superx.clickResp。
                    putBundle(
                        KEY_ISLAND,
                        Bundle().apply {
                            putInt("island.superx.template", -1)
                            putInt("island.superx.leftTemplate", 3)
                            putInt("island.superx.rightTemplate", 7)
                            putBundle(
                                "island.superx.leftInfo",
                                Bundle().apply { putParcelable("island.superx.leftInfo.icon", icon) },
                            )
                            putBundle(
                                "island.superx.rightInfo",
                                Bundle().apply {
                                    putCharSequence("island.superx.rightInfo.content", content.take(40))
                                    putParcelable("island.superx.rightInfo.clickResp", open)
                                },
                            )
                            putInt("island.superx.islandClick", 1)
                            putParcelable("island.superx.clickResp", open)
                            putString("island.superx.landingPkg", "io.github.mangi.eta")
                            putString("island.superx.landingInfo", LANDING_JSON)
                            putInt("island.superx.showTime", showSeconds.coerceIn(3, 30))
                            putInt("island.superx.islandType", 2)
                            putBoolean("island.superx.forceShow", true)
                        },
                    )
                },
            )
        }.onFailure { Log.w(TAG, "capsule extras failed: ${it.javaClass.simpleName}") }
    }

    /** 通知被撤销时用 state=0 让岛收起。 */
    fun clear(context: Context, builder: Notification.Builder) {
        runCatching {
            val capsule = Bundle().apply { putInt(KEY_STATE, 0) }
            builder.addExtras(Bundle().apply { putBundle(KEY_CAPSULE, capsule) })
        }.onFailure { Log.w(TAG, "capsule clear failed: ${it.javaClass.simpleName}") }
    }
}
