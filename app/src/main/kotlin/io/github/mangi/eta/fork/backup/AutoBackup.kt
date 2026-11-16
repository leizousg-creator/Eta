package io.github.mangi.eta.fork.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.github.mangi.eta.data.repository.EtaBackupRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自动备份：每 6 小时导出一份全量备份到「下载/EtaAutoBackup」，保留最近 3 份。
 *
 * 为什么放 Download 而不是应用私有目录：私有目录会随卸载一起消失，而备份的唯一意义
 * 就是「应用没了数据还在」。代价是对话内容会以明文 zip 落在公共目录，这台机器单人使用可接受。
 *
 * 触发方式：应用启动时检查一次是否到期（懒触发）。Eta 常驻前台服务，实际效果接近定时；
 * 不引入 WorkManager 是为了不新增依赖，也避免后台任务被系统调度策略打断。
 */
internal object AutoBackup {
    private const val TAG = "EtaAutoBackup"
    private const val PREFS = "eta_auto_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_AT = "last_at"

    /** 6 小时一次。改这里就是改频率。 */
    private const val INTERVAL_MS = 6L * 60 * 60 * 1000

    /** 只留最近 3 份。 */
    private const val RETENTION = 3

    private const val FOLDER = "EtaAutoBackup"
    private const val PREFIX = "eta-auto-"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun lastBackupAt(context: Context): Long = prefs(context).getLong(KEY_LAST_AT, 0L)

    fun intervalHours(): Int = (INTERVAL_MS / (60 * 60 * 1000)).toInt()

    /** 到期就备份一次；未开启、未到期或正在跑都直接返回。 */
    suspend fun maybeRun(context: Context): Result<String> {
        val app = context.applicationContext
        if (!isEnabled(app)) return Result.success("skipped: disabled")
        val now = System.currentTimeMillis()
        val last = lastBackupAt(app)
        if (last > 0 && now - last < INTERVAL_MS) {
            val waitMinutes = (INTERVAL_MS - (now - last)) / 60_000
            return Result.success("skipped: ${waitMinutes}m left")
        }
        return run(app, now)
    }

    /** 立即备份一次（忽略间隔），返回落地的文件名。 */
    suspend fun runNow(context: Context): Result<String> = run(context.applicationContext, System.currentTimeMillis())

    private suspend fun run(app: Context, now: Long): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) + ".zip"
            val uri = createEntry(app, name) ?: error("MediaStore 拒绝创建：$name")
            try {
                app.contentResolver.openOutputStream(uri)?.use { output ->
                    EtaBackupRepository.export(app, output)
                } ?: error("无法打开输出流")
                markReady(app, uri)
            } catch (failure: Throwable) {
                runCatching { app.contentResolver.delete(uri, null, null) }
                throw failure
            }
            prefs(app).edit().putLong(KEY_LAST_AT, now).apply()
            val removed = prune(app)
            Log.i(TAG, "auto backup ok: $name, pruned=$removed")
            name
        }.onFailure { Log.w(TAG, "auto backup failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun createEntry(app: Context, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER,
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun markReady(app: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        runCatching { app.contentResolver.update(uri, values, null, null) }
    }

    /** 只保留最近 RETENTION 份；顺带清掉上次中断留下的 IS_PENDING 残片。 */
    private fun prune(app: Context): Int {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.IS_PENDING,
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%$FOLDER%")
        val rows = mutableListOf<Triple<Long, String, Int>>()
        runCatching {
            app.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty()
                    val pending = cursor.getInt(2)
                    if (name.startsWith(PREFIX)) rows += Triple(id, name, pending)
                }
            }
        }
        if (rows.isEmpty()) return 0
        var removed = 0
        // 文件名带时间戳，按名字倒序就是最新在前。
        val sorted = rows.sortedByDescending { it.second }
        sorted.forEachIndexed { index, (id, name, pending) ->
            val stale = pending == 1
            if (stale || index >= RETENTION) {
                if (runCatching { app.contentResolver.delete(contentUri(id), null, null) }.isSuccess) {
                    removed++
                    Log.i(TAG, "pruned ${if (stale) "stale " else ""}$name")
                }
            }
        }
        return removed
    }

    private fun contentUri(id: Long): Uri =
        MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
