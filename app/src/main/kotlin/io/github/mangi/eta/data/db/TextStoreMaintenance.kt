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
            val outcome = runCatching { trimOne(dao, row) }.getOrNull() ?: return@forEach
            trimmed += outcome.first
            saved += outcome.second
        }
        return inspect(database).copy(trimmedFields = trimmed, savedChars = saved)
    }

    /**
     * 单字段瘦身：只按引用头判断越线、只读尾部分块（堆内占用以 MAX_RESTORE_CHARS 为界），
     * 删旧分块后把新引用头写回原列。旧实现丢掉了引用头，列与分块会不一致，读取会 length mismatch。
     */
    private suspend fun trimOne(dao: ConversationDao, row: TextChunkUsage): Pair<Int, Long>? {
        val keep = ChunkedTextDao.MAX_FIELD_CHARS
        val table = row.ownerTable
        val field = row.field
        val id = row.ownerId
        return when {
            table == "conversation_context_checkpoints" && field == "history" -> {
                val raw = dao.contextCheckpointRow(id) ?: return null
                val done = dao.trimToTail(table, id, field, raw.historyJson, keep) ?: return null
                dao.setCheckpointHistory(id, done.first)
                1 to done.second
            }
            table == "conversation_context_checkpoints" && field == "journal" -> {
                val raw = dao.contextCheckpointRow(id) ?: return null
                if (raw.journalJson.isBlank()) return null
                val done = dao.trimToTail(table, id, field, raw.journalJson, keep) ?: return null
                dao.setCheckpointJournal(id, done.first)
                1 to done.second
            }
            table == "conversation_messages" && field == "content" -> {
                val raw = dao.messageRow(id) ?: return null
                val done = dao.trimToTail(table, id, field, raw.content, keep) ?: return null
                dao.setMessageContent(id, done.first)
                1 to done.second
            }
            table == "conversation_messages" && field == "images" -> {
                val raw = dao.messageRow(id) ?: return null
                val done = dao.trimToTail(table, id, field, raw.imagesJson, keep) ?: return null
                dao.setMessageImages(id, done.first)
                1 to done.second
            }
            table == "conversations" && field == "runs" -> {
                val raw = dao.conversationEntityRow(id) ?: return null
                val done = dao.trimToTail(table, id, field, raw.appliedRuntimeRunIdsJson, keep) ?: return null
                dao.setConversationRuns(id, done.first)
                1 to done.second
            }
            table == "conversations" && field == "roleplay" -> {
                val raw = dao.conversationEntityRow(id) ?: return null
                val done = dao.trimToTail(table, id, field, raw.roleplayJson, keep) ?: return null
                dao.setConversationRoleplay(id, done.first)
                1 to done.second
            }
            table == "conversations" && field == "revisions" -> {
                val raw = dao.conversationEntityRow(id) ?: return null
                val done = dao.trimToTail(table, id, field, raw.revisionsJson, keep) ?: return null
                dao.setConversationRevisions(id, done.first)
                1 to done.second
            }
            else -> null
        }
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
