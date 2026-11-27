package io.github.mangi.eta.agent.island

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
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

    /**
     * 只在 vivo 系（含 iQOO）启用。
     *
     * 别的机型没有人认这些字段，而且我们为了不让顶部出现两个胶囊会关掉 Live Update，
     * 所以在非 vivo 上必须原样保留上游行为（含单测断言）。
     */
    fun isSupported(): Boolean =
        Build.MANUFACTURER.equals("vivo", ignoreCase = true) ||
            Build.BRAND.equals("vivo", ignoreCase = true) ||
            Build.MANUFACTURER.equals("iQOO", ignoreCase = true) ||
            Build.BRAND.equals("iQOO", ignoreCase = true)

    fun apply(
        context: Context,
        builder: Notification.Builder,
        content: String,
        showSeconds: Int = CAPSULE_SHOW_SECONDS,
    ) {
        if (content.isBlank()) return
        runCatching {
            val icon = Icon.createWithResource(context, R.drawable.ic_notification)
            // 岛条的点击判定靠 islandClick + clickResp：
            // 缺 islandClick 时 SystemUI 的 VivoIslandController 里 mClickedIsland 恒为 false，
            // 触摸会被 QuickStep 当普通手势吃掉，表现就是"点岛没反应"。
            val open = android.app.PendingIntent.getActivity(
                context,
                2,
                android.content.Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val seconds = showSeconds.coerceIn(3, 30)
            builder.addExtras(
                Bundle().apply {
                    // 顶层四个键决定插件「要不要走 SuperX 分支」；只写 island 子 Bundle
                    // 时插件收得到通知却不会建卡片（实测：无卡片）。
                    putInt("notification.superx.template", 4)
                    putInt("notification.superx.displays", 0x111)
                    putInt("notification.superx.operation", 0)
                    putBoolean("notification.superx.islandNotify", false)
                    putParcelable("notification.superx.clickResp", open)
                    putBundle(
                        KEY_ISLAND,
                        Bundle().apply {
                            putInt("island.superx.template", 4)
                            putInt("island.superx.leftTemplate", 1)
                            putInt("island.superx.rightTemplate", 4)
                            putInt("island.superx.showTime", seconds)
                            putBoolean("island.superx.forceShow", true)
                            putBoolean("island.superx.forceShowCard", false)
                            // 这两项是"可点击"的开关：没有它们，SystemUI 不会把触摸
                            // 交给岛，点击会落空（mClickedIsland=false）。
                            putInt("island.superx.islandClick", 1)
                            putParcelable("island.superx.clickResp", open)
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
