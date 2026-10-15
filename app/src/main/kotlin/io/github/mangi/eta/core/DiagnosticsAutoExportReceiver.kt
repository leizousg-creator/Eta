package io.github.mangi.eta.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * 诊断包自动导出的闹钟落点：到点导一次并重排下一次；
 * 收到开机、时间变更、覆盖安装时只把闹钟重排回来（不立刻导出）。
 */
internal class DiagnosticsAutoExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                if (intent.action == ACTION_EXPORT) {
                    runBlocking { DiagnosticsAutoExport.runOnce(app) }
                } else {
                    DiagnosticsAutoExport.refresh(app)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_EXPORT = "io.github.mangi.eta.action.AUTO_DIAGNOSTICS"

        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "eta-diag-export").apply { isDaemon = true }
        }
    }
}
