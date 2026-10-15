package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal data class AgentMemorySnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val lineCount: Int,
)

internal data class AgentMemoryReadResult(
    val snapshot: AgentMemorySnapshot,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val hasMore: Boolean,
    val matchedLines: Int,
)

internal sealed interface AgentMemoryMutation {
    val revision: String

    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : AgentMemoryMutation

    data class Append(
        override val revision: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Clear(
        override val revision: String,
    ) : AgentMemoryMutation
}

internal sealed interface AgentMemoryWriteResult {
    data class Success(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
    data class Conflict(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
}

internal class AgentMemoryException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 单一 MEMORY.md 的有界、原子文件存储。 */
private data class CompactCandidate(
    val position: Int,
    val start: Int,
    val end: Int,
    val bodyBytes: Int,
    val date: String,
)

internal class AgentMemoryStore(
    rootDir: File,
) {
    private val memoryDir = File(rootDir, DIRECTORY_NAME)
    private val atomicFile = AtomicFile(File(memoryDir, FILE_NAME))
    private val archiveAtomicFile = AtomicFile(File(memoryDir, ARCHIVE_FILE_NAME))
    private val lock = Any()

    fun snapshot(): AgentMemorySnapshot = synchronized(lock) {
        snapshotLocked()
    }

    fun read(
        query: String? = null,
        startLine: Int = DEFAULT_START_LINE,
        maxChars: Int = DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        if (query.isNullOrBlank()) {
            page(snapshot, startLine, boundedChars)
        } else {
            search(snapshot, query.trim(), boundedChars, archive = false).let { hot ->
                if (hot.matchedLines > 0) return@let hot
                search(snapshot, query.trim(), boundedChars, archive = true)
            }
        }
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (mutation.revision != current.revision) {
            return@synchronized AgentMemoryWriteResult.Conflict(current)
        }
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> replaceRange(current, mutation)
            is AgentMemoryMutation.Append -> append(current, mutation.content)
            is AgentMemoryMutation.Clear -> ""
        }
        val compacted = maybeCompact(updated)
        writeLocked(compacted)
        AgentMemoryWriteResult.Success(snapshotOf(compacted))
    }

    fun replaceAll(content: String): AgentMemorySnapshot = synchronized(lock) {
        val compacted = maybeCompact(content)
        writeLocked(compacted)
        snapshotOf(compacted)
    }

    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (current.revision != revision) return@synchronized AgentMemoryWriteResult.Conflict(current)
        val compacted = maybeCompact(content)
        writeLocked(compacted)
        AgentMemoryWriteResult.Success(snapshotOf(compacted))
    }

    /** 启动时收敛一次：热区超限且自动压缩开启时立即压一轮，不等待下一次写入。 */
    fun compactOnStartup() {
        synchronized(lock) {
            val current = snapshotLocked()
            val compacted = maybeCompact(current.content)
            if (compacted != current.content) {
                writeLocked(compacted)
            }
        }
    }

    /** 冷区（MEMORY-ARCHIVE.md）全文；不参与上下文注入，只供检索与归档。 */
    fun archiveSnapshot(): AgentMemorySnapshot = synchronized(lock) {
        snapshotOf(readArchiveText())
    }

    fun appendArchive(content: String): AgentMemorySnapshot = synchronized(lock) {
        if (content.isEmpty()) return@synchronized snapshotOf(readArchiveText())
        val existing = readArchiveText()
        val merged = if (existing.isEmpty()) content else existing.trimEnd('\n') + "\n" + content
        val bytes = merged.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆冷区文件超过 1 MiB 安全上限",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法创建记忆目录",
            )
        }
        val output = try {
            archiveAtomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法开始写入记忆冷区文件",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            archiveAtomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            archiveAtomicFile.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法保存记忆冷区文件",
                cause = throwable,
            )
        }
        snapshotOf(merged, bytes)
    }

    private fun readArchiveText(): String {
        val file = archiveAtomicFile.baseFile
        if (!file.exists()) return ""
        return try {
            archiveAtomicFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆冷区文件",
                cause = throwable,
            )
        }
    }

    private fun snapshotLocked(): AgentMemorySnapshot {
        val file = atomicFile.baseFile
        if (!file.exists()) return snapshotOf("")
        val bytes = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆文件",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件超过 1 MiB 安全上限",
            )
        }
        val content = bytes.toString(Charsets.UTF_8)
        return snapshotOf(content, bytes)
    }

    private fun writeLocked(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件不能超过 1 MiB UTF-8 字节",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法创建记忆目录",
            )
        }
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法开始写入记忆文件",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法保存记忆文件",
                cause = throwable,
            )
        }
    }

    private fun replaceRange(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceRange,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        if (
            mutation.startLine < 1 ||
            mutation.endLine < mutation.startLine ||
            mutation.endLine > lines.size
        ) {
            throw AgentMemoryException(
                code = "MEMORY_RANGE_INVALID",
                message = "替换行范围无效；请重新读取记忆后再试",
            )
        }
        val replacement = mutation.content.memoryLines()
        lines.subList(mutation.startLine - 1, mutation.endLine).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(mutation.startLine - 1, replacement)
        }
        return lines.joinToString("\n")
    }

    private fun append(snapshot: AgentMemorySnapshot, content: String): String {
        if (content.isEmpty()) return snapshot.content
        if (snapshot.content.isEmpty()) return content
        return snapshot.content.trimEnd('\n') + "\n" + content
    }

    @Volatile
    private var autoCompactEnabled: Boolean = true

    fun setAutoCompactEnabled(enabled: Boolean) {
        autoCompactEnabled = enabled
    }

    fun isAutoCompactEnabled(): Boolean = autoCompactEnabled

    /** 把首个 ## 标题之前的无标题旧段落整块移入冷区，消除热区盲区（旧格式段落无标题，永远无法被 compactOnce 命中）。 */
    private fun compactPrefix(content: String): String {
        val lines = content.memoryLines()
        val firstHeading = lines.indices.firstOrNull { lines[it].startsWith(SECTION_PREFIX) } ?: return content
        if (firstHeading == 0) return content
        if ((0 until firstHeading).any { lines[it].trim() == ARCHIVE_SENTINEL }) return content
        val prefixBytes = (0 until firstHeading).sumOf { lines[it].toByteArray(Charsets.UTF_8).size + 1 }
        if (prefixBytes < MIN_SECTION_BYTES) return content
        val archive = StringBuilder()
        for (index in 0 until firstHeading) archive.append(lines[index]).append('\n')
        appendArchive(archive.toString().trimEnd('\n') + "\n")
        val summary = (0 until firstHeading)
            .map { lines[it].trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("- ") }
            ?.let { if (it.length <= SUMMARY_MAX_CHARS) it else it.take(SUMMARY_MAX_CHARS) + "…" }
        val rebuilt = ArrayList<String>(lines.size - firstHeading + 2)
        if (summary != null) rebuilt += summary
        rebuilt += ARCHIVE_SENTINEL
        for (index in firstHeading until lines.size) rebuilt += lines[index]
        return rebuilt.joinToString("\n")
    }

    /** 写入后若热区超限，就把无标题前缀与最旧的段落正文移入冷区。保护段落热区保留标题 + 一行摘要，其余只留归档标记。 */
    private fun maybeCompact(content: String): String {
        if (!autoCompactEnabled) return content
        var current = normalizeSentinels(content)
        if (current.toByteArray(Charsets.UTF_8).size <= MAX_HOT_BYTES) return current
        repeat(MAX_COMPACT_ROUNDS) {
            if (current.toByteArray(Charsets.UTF_8).size <= TARGET_HOT_BYTES) return current
            var next = compactPrefix(current)
            if (next == current) next = compactOnce(current)
            if (next == current) next = compactOldestSection(current)
            if (next == current) return current
            current = next
        }
        return current
    }

    /** 单轮压缩：把最旧、正文最大的段落正文移入冷区，直到降到目标值或没有可搬段落。 */
    private fun compactOnce(content: String): String {
        val lines = content.memoryLines()
        val headings = lines.indices.filter { index -> lines[index].startsWith(SECTION_PREFIX) }
        if (headings.size < 2) return content

        val ranges = headings.mapIndexed { position, start ->
            val next = headings.getOrNull(position + 1) ?: lines.size
            start to (next - 1)
        }

        val candidates = ranges.mapIndexedNotNull { position, range ->
            val (start, end) = range
            val bodyLines = (start + 1)..end
            var bodyBytes = 0
            bodyLines.forEach { index -> bodyBytes += lines[index].toByteArray(Charsets.UTF_8).size + 1 }
            if (bodyBytes < MIN_SECTION_BYTES) return@mapIndexedNotNull null
            val date = Regex("20\\d{2}-\\d{2}").find(lines[start])?.value.orEmpty()
            CompactCandidate(position, start, end, bodyBytes, date)
        }
        if (candidates.isEmpty()) return content

        var hotBytes = content.toByteArray(Charsets.UTF_8).size
        val moved = mutableSetOf<Int>()
        val archive = StringBuilder()
        val ordered = candidates.sortedWith(compareBy({ it.date.ifEmpty { "9999-99" } }, { -it.bodyBytes }))
        for (candidate in ordered) {
            if (hotBytes <= TARGET_HOT_BYTES) break
            moved += candidate.position
            hotBytes -= candidate.bodyBytes
            if (archive.isNotEmpty()) archive.append('\n')
            archive.append(lines[candidate.start]).append('\n')
            for (index in (candidate.start + 1)..candidate.end) archive.append(lines[index]).append('\n')
        }
        if (moved.isEmpty()) return content

        val rebuilt = ArrayList<String>(lines.size)
        if (headings.first() > 0) rebuilt += lines.subList(0, headings.first())
        ranges.forEachIndexed { position, range ->
            val (start, end) = range
            rebuilt += lines[start]
            if (moved.contains(position)) {
                if (isProtectedHeading(lines[start])) {
                    summarizeBody(lines, start, end)?.let { rebuilt += "- $it" }
                }
                rebuilt += ARCHIVE_SENTINEL
            } else {
                for (index in (start + 1)..end) rebuilt += lines[index]
            }
        }
        appendArchive(archive.toString().trimEnd('\n') + "\n")
        return rebuilt.joinToString("\n")
    }

    /** 把旧版长归档标记归一化为当前短标记，一次性压低存量地板。 */
    private fun normalizeSentinels(content: String): String {
        val legacy = "- 正文已归档到 MEMORY-ARCHIVE.md（检索记忆时自动命中冷区）。"
        if (legacy == ARCHIVE_SENTINEL) return content
        return if (content.contains(legacy)) content.replace(legacy, ARCHIVE_SENTINEL) else content
    }

    /** 治本：正文已无可搬且仍超限时，把最旧的非保护段整段沉入冷区，热区留一个汇总指针行。 */
    private fun compactOldestSection(content: String): String {
        val lines = content.memoryLines()
        val headings = lines.indices.filter { lines[it].startsWith(SECTION_PREFIX) }
        if (headings.isEmpty()) return content

        val ranges = headings.mapIndexed { position, start ->
            val next = headings.getOrNull(position + 1) ?: lines.size
            start to (next - 1)
        }
        val targetIndex = ranges.indices.firstOrNull { position ->
            !isProtectedHeading(lines[ranges[position].first])
        } ?: return content
        val (start, end) = ranges[targetIndex]

        val archive = StringBuilder()
        for (index in start..end) archive.append(lines[index]).append('\n')
        appendArchive(archive.toString().trimEnd('\n') + "\n")

        val pointerRegex = Regex("""^- 更早 (\d+) 条记忆已归档""")
        var prevCount = 0
        val pointerPos = lines.indices.firstOrNull { pointerRegex.containsMatchIn(lines[it]) }
        if (pointerPos != null) {
            pointerRegex.find(lines[pointerPos])?.let { prevCount = it.groupValues[1].toInt() }
        }
        val pointerLine = "- 更早 ${prevCount + 1} 条记忆已归档（检索自动命中冷区）。"

        val kept = lines.filterIndexed { index, _ -> index != pointerPos && index !in start..end }
        val rebuilt = ArrayList<String>(kept.size + 1)
        rebuilt += pointerLine
        rebuilt += kept
        return rebuilt.joinToString("\n")
    }

    /** 取段落正文首个有意义的行作为热区摘要，超长截断。 */
    private fun summarizeBody(lines: List<String>, start: Int, end: Int): String? {
        for (index in (start + 1)..end) {
            val line = lines[index].trim()
            if (line.isEmpty()) continue
            val text = line.removePrefix("- ").trim()
            if (text.isEmpty() || text.startsWith("正文已归档")) continue
            return if (text.length <= SUMMARY_MAX_CHARS) text else text.take(SUMMARY_MAX_CHARS) + "…"
        }
        return null
    }

    private fun isProtectedHeading(heading: String): Boolean =
        PROTECTED_HEADING_KEYWORDS.any { keyword -> heading.contains(keyword) }

    private fun page(
        snapshot: AgentMemorySnapshot,
        requestedStartLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val startIndex = (requestedStartLine - 1).coerceIn(0, lines.size)
        if (startIndex >= lines.size) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val output = StringBuilder()
        var endIndex = startIndex
        while (endIndex < lines.size) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(rendered.take(maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = startIndex + 1,
            endLine = endIndex,
            hasMore = endIndex < lines.size,
            matchedLines = endIndex - startIndex,
        )
    }

    private fun search(
        snapshot: AgentMemorySnapshot,
        query: String,
        maxChars: Int,
        archive: Boolean,
    ): AgentMemoryReadResult {
        val lines = if (archive) readArchiveText().memoryLines() else snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val matched = lines.indices.filter { index ->
            lines[index].contains(query, ignoreCase = true)
        }
        if (matched.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val included = linkedSetOf<Int>()
        matched.forEach { index ->
            for (candidate in (index - SEARCH_CONTEXT_LINES)..(index + SEARCH_CONTEXT_LINES)) {
                if (candidate in lines.indices) included += candidate
            }
        }
        val output = StringBuilder(if (archive) "（冷区 $ARCHIVE_FILE_NAME）" else "")
        var lastIncluded: Int? = null
        var renderedCount = 0
        for (index in included) {
            val rendered = if (archive) "A${index + 1}: ${lines[index]}" else "${index + 1}: ${lines[index]}"
            val gap = when {
                output.isEmpty() -> ""
                archive && lastIncluded == null -> "\n"
                lastIncluded != null && index > lastIncluded + 1 -> "\n…\n"
                else -> "\n"
            }
            if (lastIncluded != null && output.length + gap.length + rendered.length > maxChars) break
            output.append(gap).append(rendered.take((maxChars - output.length - gap.length).coerceAtLeast(0)))
            lastIncluded = index
            renderedCount++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = included.firstOrNull()?.plus(1),
            endLine = lastIncluded?.plus(1),
            hasMore = renderedCount < included.size,
            matchedLines = matched.size,
        )
    }

    private fun snapshotOf(
        content: String,
        bytes: ByteArray = content.toByteArray(Charsets.UTF_8),
    ): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = sha256(bytes),
        byteSize = bytes.size,
        lineCount = content.memoryLines().size,
    )

    private fun String.memoryLines(): List<String> =
        if (isEmpty()) emptyList() else split('\n')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        const val MAX_HOT_BYTES = 48 * 1024
        const val TARGET_HOT_BYTES = 40 * 1024
        const val ARCHIVE_FILE_NAME = "MEMORY-ARCHIVE.md"
        private const val SECTION_PREFIX = "## "
        private const val MIN_SECTION_BYTES = 160
        private const val MAX_COMPACT_ROUNDS = 80
        private const val SUMMARY_MAX_CHARS = 48
        private const val ARCHIVE_SENTINEL = "- 正文已归档，检索命中冷区。"
        private val PROTECTED_HEADING_KEYWORDS = listOf(
            "环境包",
            "共享文件夹",
            "硬边界",
            "分区",
            "偏好",
            "ZenProxy",
            "白泽",
            "记忆维护规则",
            "进行中",
            "待你",
            "待用户",
            "出包",
            "签名",
            "可复用",
            "构建",
        )
        const val DEFAULT_READ_CHARS = 12_000
        const val MAX_READ_CHARS = 32_000
        const val MIN_READ_CHARS = 1
        const val MAX_WRITE_CONTENT_CHARS = 3_500
        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.md"
        private const val DEFAULT_START_LINE = 1
        private const val SEARCH_CONTEXT_LINES = 1
    }
}

internal object AgentMemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    private val autoCompactScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentMemoryStore(context.applicationContext.filesDir)
            autoCompactScope.launch {
                repeat(3) {
                    val collected = runCatching {
                        SettingsDataStore.memoryAutoCompactEnabledFlow().collect { enabled ->
                            store.setAutoCompactEnabled(enabled)
                        }
                    }.isSuccess
                    if (collected) return@launch
                    delay(1_000)
                }
            }
            // 启动即收敛：开关就绪后异步压一轮，避免存量超限记忆一直挂到下一次写入
            autoCompactScope.launch {
                delay(1_200)
                val enabled = runCatching { SettingsDataStore.settings().memoryAutoCompactEnabled }.getOrDefault(true)
                store.setAutoCompactEnabled(enabled)
                runCatching { store.compactOnStartup() }
            }
        }
    }

    fun autoCompactEnabledFlow(): Flow<Boolean> = SettingsDataStore.memoryAutoCompactEnabledFlow()

    suspend fun setAutoCompactEnabled(enabled: Boolean) {
        SettingsDataStore.setMemoryAutoCompactEnabled(enabled)
        store.setAutoCompactEnabled(enabled)
    }

    fun snapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    fun read(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.read(query, startLine, maxChars)
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        ensureInitialized()
        return store.mutate(mutation)
    }

    fun replaceAll(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    fun archiveSnapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.archiveSnapshot()
    }

    fun appendArchive(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.appendArchive(content)
    }

    fun enabledFlow(): Flow<Boolean> = SettingsDataStore.memoryEnabledFlow()

    suspend fun isEnabled(): Boolean = SettingsDataStore.settings().memoryEnabled

    suspend fun setEnabled(enabled: Boolean) = SettingsDataStore.setMemoryEnabled(enabled)

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }
}
