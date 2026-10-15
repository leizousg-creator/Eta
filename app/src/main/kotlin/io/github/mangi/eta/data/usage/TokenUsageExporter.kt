package io.github.mangi.eta.data.usage

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把用量记录导成 CSV（UTF-8 带 BOM，Excel / WPS 直接能开）。
 * 只读数据库，不改任何记录。
 */
internal object TokenUsageExporter {

    fun defaultFileName(): String =
        "eta-usage-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".csv"

    suspend fun build(context: Context): String {
        val database = EtaDatabase.get(context.applicationContext)
        val rows = database.tokenUsageDao().recordsSince(0L).sortedBy { it.createdAt }
        val titles = database.conversationDao().conversationMetadataRows()
            .associate { it.id to it.title }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val builder = StringBuilder("\uFEFF")
        builder.append("time,kind,conversation,model,context_tokens,input_tokens,output_tokens,reasoning_tokens,cached_tokens,duration_ms\n")
        rows.forEach { row ->
            builder.append(time.format(Date(row.createdAt))).append(',')
                .append(row.kind).append(',')
                .append(csv(titles[row.conversationId] ?: row.conversationId.orEmpty())).append(',')
                .append(csv(row.modelId.orEmpty())).append(',')
                .append(row.contextTokens ?: "").append(',')
                .append(row.inputTokens ?: "").append(',')
                .append(row.outputTokens ?: "").append(',')
                .append(row.reasoningTokens ?: "").append(',')
                .append(row.cachedTokens ?: "").append(',')
                .append(row.durationMs ?: "").append('\n')
        }
        return builder.toString()
    }

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            '"' + value.replace("\"", "\"\"") + '"'
        } else {
            value
        }
}
