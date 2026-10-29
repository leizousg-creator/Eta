package io.github.mangi.eta.agent.island

import android.app.Notification
import android.content.Context
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

    fun apply(
        context: Context,
        builder: Notification.Builder,
        content: String,
        showSeconds: Int = -1,
    ) {
        if (content.isBlank()) return
        runCatching {
            val icon = Icon.createWithResource(context, R.mipmap.ic_launcher)
            val capsule = Bundle().apply {
                putInt(KEY_STATE, 1)
                putString(KEY_CONTENT, content.take(40))
                putParcelable(KEY_ICON, icon)
                putInt(KEY_SHOW_TIME, showSeconds)
            }
            builder.addExtras(Bundle().apply { putBundle(KEY_CAPSULE, capsule) })
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
