package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 每行有界，文档总长不限；避免完整历史占用单个 CursorWindow 行。 */
@Entity(tableName = "agent_text_chunks", primaryKeys = ["owner_table", "owner_id", "field", "chunk_index"])
internal data class AgentTextChunkEntity(
    @ColumnInfo(name = "owner_table") val ownerTable: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val field: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    val content: String,
)

/** 由业务 DAO 在同一 Room 事务内写入主记录与分块，不暴露存储引用给领域层。 */
internal interface ChunkedTextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTextChunk(chunk: AgentTextChunkEntity)

    @Query("DELETE FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field")
    suspend fun deleteTextChunks(table: String, owner: String, field: String)

    @Query("SELECT * FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field ORDER BY chunk_index LIMIT :limit OFFSET :offset")
    suspend fun textChunks(table: String, owner: String, field: String, limit: Int, offset: Int): List<AgentTextChunkEntity>

    suspend fun storeText(table: String, owner: String, field: String, text: String): String {
        deleteTextChunks(table, owner, field)
        val capped = capText(text)
        if (capped.length <= CHUNK_CHARS && !capped.startsWith(REFERENCE_PREFIX)) return capped
        var count = 0
        var offset = 0
        while (offset < capped.length) {
            // 不把 UTF-16 代理对拆到两个 SQLite TEXT 中。
            var end = minOf(offset + CHUNK_CHARS, capped.length)
            if (end < capped.length && capped[end - 1].isHighSurrogate()) end--
            insertTextChunk(AgentTextChunkEntity(table, owner, field, count++, capped.substring(offset, end)))
            offset = end
        }
        return "$REFERENCE_PREFIX$count:${capped.length}"
    }

    /**
     * 写入侧唯一的容量裁判：单字段超过 [MAX_FIELD_CHARS] 时**保留尾部**（上下文只用最近的），
     * 并尽量落在 JSON 数组元素边界上，避免把 [ ... ] 切坏。
     * JSON 对象与其它结构不明的文本不截断，宁可留大也不写坏。
     */
    fun capText(text: String): String {
        if (text.length <= MAX_FIELD_CHARS) return text
        if (text.startsWith("{")) return text
        val cut = text.length - MAX_FIELD_CHARS
        if (text.startsWith("[")) {
            // 对象数组优先落在元素边界："," 的下标 +2 才是下一个元素的 '{'，+1 会把 '}' 留在数组开头。
            val element = text.indexOf("},{", cut)
            if (element in 0 until text.length - 2) return "[" + text.substring(element + 2)
            // 字符串数组退到逗号边界；但逗号后必须是引号或对象，否则原样返回而不是切出半个元素。
            val comma = text.indexOf(',', cut)
            if (comma in 0 until text.length - 1) {
                val head = text.substring(comma + 1).trimStart()
                if (head.startsWith("\"") || head.startsWith("{")) return "[" + head
            }
            return text
        }
        return text.substring(cut)
    }

    /** 只读引用头就能拿到字符数，体量体检不必把正文读进堆。 */
    fun storedCharCount(stored: String): Int {
        if (!stored.startsWith(REFERENCE_PREFIX)) return stored.length
        val parts = stored.removePrefix(REFERENCE_PREFIX).split(':')
        if (parts.size != 2) return 0
        return parts[1].toIntOrNull() ?: 0
    }

    suspend fun restoreText(table: String, owner: String, field: String, stored: String): String {
        if (!stored.startsWith(REFERENCE_PREFIX)) return stored
        val parts = stored.removePrefix(REFERENCE_PREFIX).split(':')
        check(parts.size == 2) { "Invalid history payload reference" }
        val count = parts[0].toInt()
        val length = parts[1].toInt()
        check(count > 0 && length > 0) { "Invalid history payload size" }
        // 一次到位，避免边拼边翻倍带来的额外拷贝峰值。
        val result = StringBuilder(length)
        var offset = 0
        while (offset < count) {
            val page = textChunks(table, owner, field, minOf(32, count - offset), offset)
            check(page.isNotEmpty()) { "History payload chunk missing" }
            page.forEach { chunk ->
                check(chunk.chunkIndex == offset++) { "History payload chunk out of order" }
                result.append(chunk.content)
            }
        }
        check(result.length == length) { "History payload length mismatch" }
        return result.toString()
    }

    /** 引用头（块数, 字符数）；非引用返回其自身长度。字符数按 UTF-16 单元计，与 SQLite 的 LENGTH() 不同。 */
    fun parseReference(stored: String): Pair<Int, Int>? {
        if (!stored.startsWith(REFERENCE_PREFIX)) return 1 to stored.length
        val parts = stored.removePrefix(REFERENCE_PREFIX).split(':')
        if (parts.size != 2) return null
        val count = parts[0].toIntOrNull() ?: return null
        val length = parts[1].toIntOrNull() ?: return null
        if (count <= 0 || length <= 0) return null
        return count to length
    }

    /**
     * 只读尾部分块：单次进堆的字符数以 [maxChars] 为界。
     * 越线判断只信引用头，不用 SQL 求和（两者计法不同，混用会造成假的不匹配）。
     */
    suspend fun restoreTextTail(table: String, owner: String, field: String, stored: String, maxChars: Int): String {
        if (!stored.startsWith(REFERENCE_PREFIX)) return stored
        val ref = parseReference(stored) ?: return ""
        val count = ref.first
        if (ref.second <= maxChars) return restoreText(table, owner, field, stored)
        val wanted = maxChars / CHUNK_CHARS + 2
        var start = maxOf(0, count - wanted)
        val kept = ArrayList<AgentTextChunkEntity>(wanted)
        while (start < count) {
            val page = textChunks(table, owner, field, minOf(32, count - start), start)
            if (page.isEmpty()) break
            kept.addAll(page)
            start += page.size
            if (kept.sumOf { it.content.length } >= maxChars) break
        }
        val buffer = StringBuilder(maxChars + CHUNK_CHARS * 2)
        kept.forEach { buffer.append(it.content) }
        if (buffer.length <= maxChars) return buffer.toString()
        // 任意位置截断会把首个元素腰斩；统一过一遍元素边界，交出去的必须是能解析的数组开头。
        return trimToElementBoundary(buffer.substring(buffer.length - maxChars))
    }

    /**
     * 瘦身：只保留尾部 [keepChars] 字符，删旧分块后写回，返回（新引用头, 省下的字符数）。
     * 调用方必须把新引用头写回原列，否则列里的头与分块不一致，下次读取会 length mismatch。
     */
    suspend fun trimToTail(table: String, owner: String, field: String, stored: String, keepChars: Int): Pair<String, Long>? {
        val ref = parseReference(stored) ?: return null
        if (ref.second <= keepChars) return null
        val tail = restoreTextTail(table, owner, field, stored, keepChars)
        if (tail.isEmpty()) return null
        val kept = trimToElementBoundary(tail)
        if (kept.isEmpty() || kept.length >= ref.second) return null
        deleteTextChunks(table, owner, field)
        val header = storeText(table, owner, field, kept)
        return header to (ref.second - kept.length).toLong()
    }

    /**
     * 把「从中间切出来的尾巴」修成合法 JSON 数组开头，绝不交出 [,{ / [},{ 这类坏头：
     * 1) 已经是 "[{" 合法数组头 —— 原样返回（幂等）；
     * 2) '[' 与首个 '{' 之间只剩 , } ] 空白等残渣（含 [,{ 、[},{ ）—— 丢掉残渣，从完整对象 '{' 起重包 '['；
     * 3) 首个 '{' 前面还有正文（被腰斩的元素）—— 跳到下一个 "},{" 之后的 '{'；
     * 4) 都做不到就退回空数组，宁可少一段历史也不把解析不了的 JSON 交出去。
     */
    fun trimToElementBoundary(text: String): String {
        if (text.isBlank()) return text
        if (text.startsWith("[{")) return text
        val head = text.indexOf('{')
        if (head < 0) return "[]"
        val residue = text.substring(0, head).all {
            it == '[' || it == ']' || it == ',' || it == '}' || it.isWhitespace()
        }
        if (residue) return "[" + text.substring(head)
        val boundary = text.indexOf("},{", head)
        if (boundary >= 0 && boundary + 2 < text.length) return "[" + text.substring(boundary + 2)
        return "[]"
    }

    companion object {
        private const val CHUNK_CHARS = 16_384
        /** 单字段容量上限：1200 万字符（约 24 MB 原文，读入峰值约 50 MB，512 MB 堆内安全）。 */
        const val MAX_FIELD_CHARS = 12_000_000
        /** 单次读入窗口：200 万字符（约 4 MB 堆内）；超出的历史只取尾部，不再整串进堆。 */
        const val MAX_RESTORE_CHARS = 2_000_000
        const val REFERENCE_PREFIX = "@eta:chunks:v1:"
    }
}
