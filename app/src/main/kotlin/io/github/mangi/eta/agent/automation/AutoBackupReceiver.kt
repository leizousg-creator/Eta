package io.github.mangi.eta.agent.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.data.repository.AutoBackupRunner
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * 自动备份的闹钟落点：到点跑一次备份并重排下一次；
 * 收到开机、时间变更、覆盖安装时只负责把闹钟重排回来（不触发备份判断）。
 */
internal class AutoBackupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                if (intent.action == ACTION_BACKUP) {
                    runBlocking {
                        AutoBackupRunner.maybeRun(app)
                        AutoBackupAlarm.refresh(app)
                    }
                } else {
                    runBlocking { AutoBackupAlarm.refresh(app) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_BACKUP = "io.github.mangi.eta.action.AUTO_BACKUP"

        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "eta-auto-backup").apply { isDaemon = true }
        }
    }
}
