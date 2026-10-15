package io.github.mangi.eta.data.db

import android.content.Context
import java.io.File

/** agent_text_chunks 里一个 (表, 所有者, 字段) 的体量。 */
internal data class TextChunkUsage(
    @androidx.room.ColumnInfo(name = "owner_table") val ownerTable: String,
    @androidx.room.ColumnInfo(name = "owner_id") val ownerId: String,
    @androidx.room.ColumnInfo(name = "field") val field: String,
    @androidx.room.ColumnInfo(name = "chunk_count") val chunkCount: Int,
    @androidx.room.ColumnInfo(name = "char_count") val charCount: Long,
)

/**
 * 文本库体量体检与瘦身：超上限的历史字段按写入侧同一条规则保尾截断，删掉分块后重写引用头。
 * 只碰 agent_text_chunks 与引用头本身，不动会话、消息、分类与设置。
 */
internal object TextStoreMaintenance {

    private const val FLAG = "textstore-maintenance-v1.flag"

    internal data class Report(
        val scannedFields: Int,
        val scannedChars: Long,
        val oversizedFields: Int,
        val trimmedFields: Int,
        val savedChars: Long,
        val topFields: List<TextChunkUsage>,
    )

    suspend fun inspect(database: EtaDatabase): Report {
        val dao = database.conversationDao()
        val rows = dao.chunkUsage()
        val limit = ChunkedTextDao.MAX_FIELD_CHARS.toLong()
        return Report(
            scannedFields = rows.size,
            scannedChars = rows.sumOf { it.charCount },
            oversizedFields = rows.count { it.charCount > limit },
            trimmedFields = 0,
            savedChars = 0,
            topFields = rows.take(3),
        )
    }

    suspend fun trimOversized(database: EtaDatabase): Report {
        val dao = database.conversationDao()
        val rows = dao.chunkUsage()
        val limit = ChunkedTextDao.MAX_FIELD_CHARS.toLong()
        var trimmed = 0
        var saved = 0L
        rows.filter { it.charCount > limit }.forEach { row ->
            val stored = "${ChunkedTextDao.REFERENCE_PREFIX}${row.chunkCount}:${row.charCount}"
            val text = runCatching { dao.restoreText(row.ownerTable, row.ownerId, row.field, stored) }.getOrNull() ?: return@forEach
            val capped = dao.capText(text)
            if (capped.length >= text.length) return@forEach
            dao.storeText(row.ownerTable, row.ownerId, row.field, capped)
            trimmed += 1
            saved += (text.length - capped.length).toLong()
        }
        return inspect(database).copy(trimmedFields = trimmed, savedChars = saved)
    }

    /** 升级后第一次起动跑一次，之后靠体检与「一键瘦身」按需跑。 */
    suspend fun runOnceIfNeeded(context: Context) {
        val flag = File(context.filesDir, FLAG)
        if (flag.exists()) return
        val report = runCatching { trimOversized(EtaDatabase.get(context)) }.getOrNull() ?: return
        runCatching {
            flag.writeText(
                "fields=${report.scannedFields} oversized=${report.oversizedFields} " +
                    "trimmed=${report.trimmedFields} saved=${report.savedChars}",
            )
        }
    }
}
