package io.github.mangi.eta.agent.island

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import io.github.mangi.eta.R

/**
 * 把 Eta 的进行中状态写进 vivo「原子岛（超级岛）」能识别的通知扩展字段。
 *
 * 字段集来自实测可用的第三方模块 EtaRedirect（`com.eta.copilotredirect` 的 `addSuperX`），
 * 并在本机验证：插件 `SuperXCapsuleDataController` 确实收到了通知。
 *
 * 关键点（踩过的坑）：
 *  - 只写 `notification.superx.island` 子 Bundle 是不够的：插件收得到通知，
 *    但不会建卡片（实测无任何岛元素）。必须先给顶层四个键
 *    `template=4` / `displays=0x111` / `operation` / `islandNotify`，
 *    插件才会走 SuperX 分支。
 *  - `island.superx.template` 用 4，`leftTemplate=1` / `rightTemplate=4`；
 *    早期用的 `-1` / `3` / `7` 组合在这个 ROM 版本上不显示。
 *  - `leftInfo` / `rightInfo` 与两个 template 必填，缺一项插件直接 return。
 *  - `forceShow` 必须为 true，`forceShowCard` 为 false：只显示胶囊，不强制弹卡片。
 *
 * 整段包在 runCatching 里：字段不被识别最多是没卡片，不影响 Eta 自己的普通通知。
 */
internal object VivoCapsuleExtras {
    private const val TAG = "EtaCapsule"
    private const val KEY_ISLAND = "notification.superx.island"
    private const val CAPSULE_TITLE = "Eta"

    /** 岛卡片的停留秒数；运行期间每次通知更新都会续期。调整体验时只改这里。 */
    const val CAPSULE_SHOW_SECONDS = 8

    fun apply(
        context: Context,
        builder: Notification.Builder,
        content: String,
        showSeconds: Int = CAPSULE_SHOW_SECONDS,
    ) {
        if (content.isBlank()) return
        runCatching {
            val icon = Icon.createWithResource(context, R.drawable.ic_notification)
            val seconds = showSeconds.coerceIn(3, 30)
            builder.addExtras(
                Bundle().apply {
                    // 顶层四个键决定插件「要不要走 SuperX 分支」；只写 island 子 Bundle
                    // 时插件收得到通知却不会建卡片（实测：无卡片）。
                    putInt("notification.superx.template", 4)
                    putInt("notification.superx.displays", 0x111)
                    putInt("notification.superx.operation", 0)
                    putBoolean("notification.superx.islandNotify", false)
                    putBundle(
                        KEY_ISLAND,
                        Bundle().apply {
                            putInt("island.superx.template", 4)
                            putInt("island.superx.leftTemplate", 1)
                            putInt("island.superx.rightTemplate", 4)
                            putInt("island.superx.showTime", seconds)
                            putBoolean("island.superx.forceShow", true)
                            putBoolean("island.superx.forceShowCard", false)
                            putBundle(
                                "island.superx.leftInfo",
                                Bundle().apply {
                                    putCharSequence("island.superx.leftInfo.content", CAPSULE_TITLE)
                                    putParcelable("island.superx.leftInfo.icon", icon)
                                },
                            )
                            putBundle(
                                "island.superx.rightInfo",
                                Bundle().apply {
                                    putCharSequence("island.superx.rightInfo.content", content.take(40))
                                },
                            )
                        },
                    )
                    putBundle(
                        "notification.superx.baseInfos",
                        Bundle().apply {
                            putString("notification.superx.baseInfos.title", CAPSULE_TITLE)
                            putString("notification.superx.baseInfos.content", content.take(80))
                        },
                    )
                    putBundle(
                        "notification.superx.shortInfos",
                        Bundle().apply {
                            putString("notification.superx.shortInfos.coreInfoShort", content.take(40))
                            putString("notification.superx.shortInfos.describeShort", CAPSULE_TITLE)
                        },
                    )
                    putBundle(
                        "notification.superx.capsule",
                        Bundle().apply {
                            putParcelable("notification.superx.capsule.icon", icon)
                        },
                    )
                },
            )
        }.onFailure { Log.w(TAG, "island extras failed: ${it.javaClass.simpleName}") }
    }
}
