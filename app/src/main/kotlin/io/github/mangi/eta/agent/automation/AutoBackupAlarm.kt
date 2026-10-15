package io.github.mangi.eta.agent.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AutoBackupInterval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自动备份的定时器：按周期排**一次**不确定闹钟（[AlarmManager.setAndAllowWhileIdle]），
 * 到点唤醒一次跑备份，跑完再排下一次；关掉开关就立刻取消。
 *
 * 只有闹钟，没有常驻进程或前台服务：周期 24 小时时一天最多多一次唤醒，
 * 真正的开销是那一次导出，而导出本身在数据没变化时会被 [io.github.mangi.eta.data.repository.AutoBackupRunner] 跳过。
 * 设备重启、时间变更或覆盖安装后由 [AutoBackupReceiver] 重排，防止闹钟被系统清掉后再也不跑。
 */
internal object AutoBackupAlarm {

    private const val REQUEST_CODE = 0x0E7A

    /** 最短触发间隔，避免到期时间已过导致立刻连环触发。 */
    private const val MIN_DELAY_MS = 60_000L

    /** 按当前设置重排（关闭时等价于取消）。 */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
        if (settings == null || !settings.autoBackupEnabled) {
            cancel(app)
            return@withContext
        }
        val intervalMs = AutoBackupInterval.millis(settings.autoBackupIntervalMinutes, settings.autoBackupIntervalHours)
        val now = System.currentTimeMillis()
        val due = if (settings.autoBackupLastAt > 0L) settings.autoBackupLastAt + intervalMs else now
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
            AndroidAgentLogger.warn("Auto backup alarm unavailable: type=${it.safeLogType()}")
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { app.getSystemService(AlarmManager::class.java).cancel(pendingIntent(app)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AutoBackupReceiver::class.java).setAction(AutoBackupReceiver.ACTION_BACKUP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
