package io.github.mangi.eta.agent.voice

/**
 * 把朗读用的可读片段定位回原文区间，供"读到哪亮到哪"使用。
 *
 * 可读文本丢掉了 Markdown 标记，与原文偏移并不一一对应；这里按顺序在原文里查找片段，
 * 定位失败（跨标记、被合并的空格等）就返回 null，让高亮跳过该片段而不是整体错位。
 */
internal object SpeechSegmentLocator {

    fun locate(original: String, chunks: List<String>): List<IntRange?> {
        var cursor = 0
        return chunks.map { chunk ->
            val range = find(original, chunk, cursor)
            if (range != null) cursor = range.last + 1
            range
        }
    }

    private fun find(original: String, chunk: String, from: Int): IntRange? {
        val probe = chunk.trim()
        if (probe.isEmpty()) return null
        // 先整段匹配，再退到短前缀：短前缀只为定位所在位置，高亮区间仍按长度推断。
        for (size in intArrayOf(probe.length, PROBE_LONG, PROBE_SHORT)) {
            if (size <= 0 || size > probe.length) continue
            val needle = probe.take(size)
            if (needle.isBlank()) continue
            val at = original.indexOf(needle, from)
            if (at >= 0) return at until (at + size)
        }
        return null
    }

    private const val PROBE_LONG = 12
    private const val PROBE_SHORT = 6
}
