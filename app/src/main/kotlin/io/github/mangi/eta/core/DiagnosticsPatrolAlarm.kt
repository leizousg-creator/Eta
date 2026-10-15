package io.github.mangi.eta.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 极致自动排查的定时器：按周期排**一次**闹钟，到点巡检一次，跑完再排下一次。
 *
 * 与自动备份同一套做法：优先精确闹钟（[AlarmManager.setExactAndAllowWhileIdle]），
 * 系统没给精确闹钟权限时退回 [AlarmManager.setAndAllowWhileIdle]，不报错、不弹窗；
 * 没有任何常驻进程或前台服务，关掉开关就立刻取消。
 */
internal object DiagnosticsPatrolAlarm {

    private const val REQUEST_CODE = 0x0E7C

    /** 最短延迟，避免到期时间已过导致立刻连环触发。 */
    private const val MIN_DELAY_MS = 60_000L

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
        if (settings == null || !settings.diagPatrolEnabled) {
            cancel(app)
            return@withContext
        }
        val intervalMs = DiagnosticsPatrolInterval.millis(settings.diagPatrolIntervalMinutes)
        val now = System.currentTimeMillis()
        val due = if (settings.diagPatrolLastAt > 0L) settings.diagPatrolLastAt + intervalMs else now
        val triggerAt = maxOf(due, now + MIN_DELAY_MS)
        runCatching {
            val manager = app.getSystemService(AlarmManager::class.java)
            val exact = runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(app))
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(app))
            }
        }.onFailure {
            AndroidAgentLogger.warn("Diagnostics patrol alarm unavailable: type=${it.safeLogType()}")
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { app.getSystemService(AlarmManager::class.java).cancel(pendingIntent(app)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, DiagnosticsPatrolReceiver::class.java).setAction(DiagnosticsPatrolReceiver.ACTION_PATROL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
