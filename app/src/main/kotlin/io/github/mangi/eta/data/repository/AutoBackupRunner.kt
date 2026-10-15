package io.github.mangi.eta.data.repository

import android.content.Context
import android.os.Environment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AutoBackupInterval
import io.github.mangi.eta.data.model.AutoBackupKeepCount
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 自动备份的结果，供界面提示与日志使用。 */
internal sealed interface AutoBackupResult {
    data class Done(val fileName: String, val conversationCount: Int, val kept: Int) : AutoBackupResult
    data class Skipped(val reason: String) : AutoBackupResult
    data class Failed(val message: String) : AutoBackupResult
}

/**
 * 定期自动备份：把 Eta 数据导出到共享目录的 `Eta备份/` 下，并按保留份数删掉最旧的。
 *
 * 复用「数据备份」页同一份导出实现（[EtaBackupRepository.export]），产物与手动导出完全一致，
 * 需要还原时直接走「数据备份 → 导入」即可。
 *
 * 省电策略：先比较一份轻量指纹（会话数 / 消息数 / 用量记录数），内容没变就只推进检查时间、
 * 不写文件，避免一天一次的白写盘。到点唤醒由 [io.github.mangi.eta.agent.automation.AutoBackupAlarm] 负责。
 */
internal object AutoBackupRunner {

    const val DIR_NAME = "Eta备份"
    private const val FILE_PREFIX = "Eta-自动备份-"
    private const val FILE_SUFFIX = ".eta-backup.json"

    /** 默认备份目录（忽略自定义配置）。 */
    fun defaultDirectory(): File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    /**
     * 备份目录：自定义路径优先——绝对路径直接用，相对路径按内部存储根解析；为空时用默认目录。
     * 默认参数取 [SettingsDataStore.cachedAutoBackupDirectory]，供界面等非挂起调用使用。
     */
    fun directory(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): File {
        val trimmed = customDirectory.trim()
        if (trimmed.isEmpty()) return defaultDirectory()
        return if (trimmed.startsWith("/")) File(trimmed)
        else File(Environment.getExternalStorageDirectory(), trimmed)
    }

    /** 已存在的自动备份文件，最新在前。 */
    fun backups(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): List<File> = directory(customDirectory)
        .listFiles { file: File ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
        }
        ?.sortedByDescending { it.name }
        .orEmpty()

    /**
     * 到点且内容有变化才执行；[force] 为 true 时忽略开关、周期与指纹（手动「立即备份一次」）。
     * 任何失败都只记录日志，不抛出，避免影响启动或闹钟回调。
     */
    suspend fun maybeRun(context: Context, force: Boolean = false): AutoBackupResult =
        withContext(Dispatchers.IO) {
            val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
                ?: return@withContext AutoBackupResult.Skipped("设置不可读")
            if (!force && !settings.autoBackupEnabled) {
                return@withContext AutoBackupResult.Skipped("未开启自动备份")
            }
            val intervalMinutes = AutoBackupInterval.effectiveMinutes(
                settings.autoBackupIntervalMinutes,
                settings.autoBackupIntervalHours,
            )
            val intervalMs = intervalMinutes * 60_000L
            val now = System.currentTimeMillis()
            if (!force && settings.autoBackupLastAt > 0L && now - settings.autoBackupLastAt < intervalMs) {
                return@withContext AutoBackupResult.Skipped("未到备份周期")
            }
            val fingerprint = fingerprint(context)
            // 短周期（≤ 60 分钟）时每次都写一份，保证「每 10 分钟一份」不会被内容指纹挡掉。
            val alwaysWrite = intervalMinutes <= AutoBackupInterval.ALWAYS_WRITE_MAX_MINUTES
            if (!force && !alwaysWrite && fingerprint.isNotEmpty() && fingerprint == settings.autoBackupFingerprint) {
                runCatching {
                    SettingsDataStore.setAutoBackupState(
                        lastAt = now,
                        fingerprint = fingerprint,
                        fileName = settings.autoBackupLastFileName,
                    )
                }
                return@withContext AutoBackupResult.Skipped("数据没有变化")
            }
            runNow(
                context,
                AutoBackupKeepCount.normalize(settings.autoBackupKeepCount),
                fingerprint,
                settings.autoBackupDirectory,
            )
        }

    /** 轻量指纹：只读三个计数，不碰数据库文件本身。取不到时返回空串（空串不做跳过判断）。 */
    private suspend fun fingerprint(context: Context): String = runCatching {
        val database = EtaDatabase.get(context)
        val conversations = database.conversationDao().conversationCount()
        val messages = database.conversationDao().messageCount()
        val usage = database.tokenUsageDao().recordCount()
        "c=$conversations;m=$messages;u=$usage"
    }.getOrDefault("")

    private suspend fun runNow(
        context: Context,
        keep: Int,
        fingerprint: String,
        customDirectory: String,
    ): AutoBackupResult {
        val dir = directory(customDirectory)
        if (!dir.exists() && !dir.mkdirs()) {
            return AutoBackupResult.Failed("无法创建备份目录 " + dir.absolutePath)
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
        return try {
            val summary = target.outputStream().use { stream ->
                EtaBackupRepository.export(context, stream)
            }
            val kept = prune(keep, customDirectory)
            runCatching {
                SettingsDataStore.setAutoBackupState(
                    lastAt = System.currentTimeMillis(),
                    fingerprint = fingerprint,
                    fileName = target.name,
                )
            }
            AutoBackupResult.Done(target.name, summary.conversationCount, kept)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            runCatching { target.delete() }
            AndroidAgentLogger.warn("Auto backup failed: type=${error.safeLogType()}")
            AutoBackupResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun prune(keep: Int, customDirectory: String): Int {
        backups(customDirectory).drop(keep.coerceAtLeast(1)).forEach { it.delete() }
        return backups(customDirectory).size
    }
}
