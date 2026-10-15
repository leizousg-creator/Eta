package io.github.mangi.eta.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 诊断包定期自动导出：每周一次把诊断包直接落到「诊断包备份」目录，省得每次手动点。
 *
 * 只用不确定闹钟，没有常驻进程；到点唤醒一次，导完再排下一次。目录写不进去
 * （没给所有文件访问权限等）就静默跳过，不报错、不打扰；只保留最近 [KEEP_FILES] 个，
 * 免得备份目录越堆越大。
 */
internal object DiagnosticsAutoExport {

    private const val REQUEST_CODE = 0x0E7B
    private const val INTERVAL_MS = 7 * 24 * 3_600_000L
    private const val MIN_DELAY_MS = 60_000L
    private const val KEEP_FILES = 4
    private const val FILE_PREFIX = "Eta-诊断包-"

    /** 自动导出单独放一个子目录：只在这里滚动保留，绝不碰你手动存下的诊断包。 */
    private const val BACKUP_DIR = "/storage/emulated/0/ai共享文件夹/诊断包备份/自动"

    /** 按上次导出时间排下一次；从没导出过就先在 1 分钟后补一次。 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val last = latestExportTime()
        val due = if (last > 0L) last + INTERVAL_MS else now + MIN_DELAY_MS
        runCatching {
            app.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(due, now + MIN_DELAY_MS), pending(app))
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { app.getSystemService(AlarmManager::class.java).cancel(pending(app)) }
    }

    /** 导出一次并重排下一次；失败只留一条日志，不弹通知。 */
    suspend fun runOnce(context: Context) {
        val app = context.applicationContext
        runCatching {
            val bytes = DiagnosticsBundle.collect(app)
            val dir = File(BACKUP_DIR).apply { mkdirs() }
            val target = File(dir, DiagnosticsBundle.defaultFileName())
            target.writeBytes(bytes)
            EtaTrace.log("diag.auto", "已自动导出 ${target.name} ${bytes.size} 字节")
            prune(dir, target.name)
        }.onFailure {
            EtaTrace.log("diag.auto.fail", "自动导出失败：${it.javaClass.simpleName}")
        }
        refresh(app)
    }

    private fun latestExportTime(): Long = runCatching {
        File(BACKUP_DIR).listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".zip") }
            ?.maxOfOrNull { it.lastModified() }
            ?: 0L
    }.getOrDefault(0L)

    private fun prune(dir: File, keepName: String) {
        runCatching {
            val olds = dir.listFiles()
                ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            olds.drop(KEEP_FILES).forEach { file -> if (file.name != keepName) file.delete() }
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, DiagnosticsAutoExportReceiver::class.java)
            .setAction(DiagnosticsAutoExportReceiver.ACTION_EXPORT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
