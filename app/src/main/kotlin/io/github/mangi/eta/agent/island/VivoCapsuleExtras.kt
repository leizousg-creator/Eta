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
 * 把 Eta 的进行中状态写进 vivo「原子岛（超级岛）」能识别的通知扩展字段。
 *
 * 依据（反编译 SystemUI 插件 `com.vivo.systemuiplugin.superx.statusbar.SuperXCapsuleDataController`
 * 的 `addAtomIslandElementToShow`）：
 *
 *  - 只认 `notification.superx.island` 这个 Bundle；
 *  - `island.superx.leftTemplate` / `rightTemplate` 与 `leftInfo` / `rightInfo` 为必填，
 *    缺一项会直接 return（日志 `island date error …`）；
 *  - 点击行为由 `island.superx.islandClick`（1 = 触发响应）与
 *    `island.superx.clickResp`（PendingIntent）共同决定；
 *  - 落地包与深链在 `island.superx.landingPkg` / `landingInfo`；
 *  - `island.superx.showTime` 为停留秒数，-1 会取系统默认（近似永久），这里固定用有界值。
 *
 * 早期版本还写过 `notification.superx.capsule`，但那是「不支持原子岛」设备的精简分支，
 * 点击字段不生效，而且会让插件的三路监听器对同一 key 重复投递 —— 已移除。
 *
 * 整段包在 runCatching 里：字段不被识别最多是没卡片，不影响 Eta 自己的普通通知。
 */
internal object VivoCapsuleExtras {
    private const val TAG = "EtaCapsule"
    private const val KEY_ISLAND = "notification.superx.island"

    /** 岛卡片的停留秒数；运行期间每次通知更新都会续期。调整体验时只改这里。 */
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
            val seconds = showSeconds.coerceIn(3, 30)
            builder.addExtras(
                Bundle().apply {
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
                            putInt("island.superx.showTime", seconds)
                            putInt("island.superx.islandType", 2)
                            putBoolean("island.superx.forceShow", true)
                        },
                    )
                },
            )
        }.onFailure { Log.w(TAG, "island extras failed: ${it.javaClass.simpleName}") }
    }
}
