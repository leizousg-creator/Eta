package io.github.mangi.eta.fork.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.hook.vivo.VivoIslandNotifications
import io.github.mangi.eta.hook.vivo.VivoIslandWire
import io.github.mangi.eta.ui.MainActivity

/**
 * 原子岛降级通道：模块注入不到 copilot 进程时，改用「媒体会话 + MediaStyle 通知」。
 *
 * 背景：原生桥接需要 LSPosed 模块 hook com.vivo.ai.copilot 的 WidgetTaskService.onBind，
 * 但这台设备上 copilot 进程始终拿不到注入（systemui 能拿到），于是 bind 回来的是原生
 * binder，只能退回普通通知。vivo 的原子岛对媒体会话有系统级支持，所以这里把 Agent 的
 * 运行状态包装成一个"正在播放"的媒体元数据，争取让系统把它当成音乐卡片显示到岛上。
 *
 * 它不影响任务执行：任何一步失败都只是少一张卡片。
 */
internal object MediaSessionIslandFallback {
    private const val CHANNEL_ID = "eta_island_fallback"
    private const val CHANNEL_NAME = "Eta 岛状态"
    private const val NOTIFICATION_ID = 0xE7A100

    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var started = false
    private var lastTitle: String? = null

    /** 桥接判定不可用时调用一次，之后靠 update/finish 刷新。 */
    fun show(context: Context, state: VivoIslandNotifications.State, progress: VivoIslandWire.Progress) {
        val app = context.applicationContext
        if (!started) {
            started = true
            createChannel(app)
            session = runCatching {
                MediaSession(app, "Eta").apply {
                    setCallback(object : MediaSession.Callback() {}, main)
                    isActive = true
                }
            }.getOrNull()
            AndroidAgentLogger.info("island fallback ready：媒体会话壳已建立")
        }
        render(app, state, progress)
    }

    fun update(context: Context, state: VivoIslandNotifications.State, progress: VivoIslandWire.Progress) {
        if (!started) return
        render(context.applicationContext, state, progress)
    }

    fun finish(context: Context) {
        if (!started) return
        val app = context.applicationContext
        runCatching {
            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0L, 1f)
                    .build(),
            )
        }
        main.postDelayed({ clear(app) }, 8_000L)
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        started = false
        lastTitle = null
        main.removeCallbacksAndMessages(null)
        runCatching {
            NotificationManagerCompatShim.cancel(app, NOTIFICATION_ID)
            session?.isActive = false
            session?.release()
        }
        session = null
    }

    private fun render(app: Context, state: VivoIslandNotifications.State, progress: VivoIslandWire.Progress) {
        val title = titleOf(progress)
        val text = subtitleOf(state, progress)
        if (title == lastTitle && state == VivoIslandNotifications.State.RUNNING) {
            // 同一阶段不重复刷通知，避免通知栏抖动。
            return
        }
        lastTitle = title
        val playing = state == VivoIslandNotifications.State.RUNNING
        runCatching {
            session?.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "Eta")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 0L)
                    .build(),
            )
            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0L, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                    .build(),
            )
            NotificationManagerCompatShim.notify(app, NOTIFICATION_ID, buildNotification(app, title, text))
        }
    }

    private fun buildNotification(app: Context, title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        val token = session?.sessionToken
        if (token != null) {
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(),
            )
        }
        return builder.build()
    }

    private fun createChannel(app: Context) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "以媒体卡片形式显示 Agent 运行状态（原子岛降级）"
                setShowBadge(false)
            },
        )
    }

    private fun titleOf(progress: VivoIslandWire.Progress): String = when (progress.phase) {
        VivoIslandWire.Phase.PREPARING -> "准备运行"
        VivoIslandWire.Phase.THINKING -> "正在思考"
        VivoIslandWire.Phase.ANSWERING -> "正在回答"
        VivoIslandWire.Phase.RETRYING -> "请求重试中"
        VivoIslandWire.Phase.COMPACTING -> "整理上下文"
        VivoIslandWire.Phase.TOOL -> progress.tool.ifBlank { "执行工具" }
    }

    private fun subtitleOf(state: VivoIslandNotifications.State, progress: VivoIslandWire.Progress): String {
        val suffix = if (progress.virtualScreen) " · 虚拟屏" else ""
        return when (state) {
            VivoIslandNotifications.State.RUNNING -> "Eta 运行中$suffix"
            VivoIslandNotifications.State.COMPLETED -> "已完成$suffix"
            VivoIslandNotifications.State.CANCELLED -> "已停止$suffix"
            VivoIslandNotifications.State.FAILED -> "执行失败$suffix"
        }
    }
}

/** 极简通知收起封装，避免为了两行调用引入 compat 依赖。 */
private object NotificationManagerCompatShim {
    fun notify(context: Context, id: Int, notification: Notification) {
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }
}
