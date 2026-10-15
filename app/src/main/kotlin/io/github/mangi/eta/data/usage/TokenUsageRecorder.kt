package io.github.mangi.eta.data.usage

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.TokenUsageRecordEntity

/**
 * 把每次模型请求的用量与降档重试落库，供「用量统计」聚合展示。
 * 只做追加写，失败不影响对话；保留窗口外的旧记录顺手清掉。
 */
internal object TokenUsageRecorder {
    const val KIND_USAGE = "usage"
    const val KIND_DOWNGRADE = "downgrade"

    /** 保留 90 天，超出即清，避免统计表无限增长。 */
    private const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000

    suspend fun recordUsage(
        context: Context,
        conversationId: String?,
        modelId: String?,
        contextTokens: Int?,
        inputTokens: Int?,
        outputTokens: Int?,
        reasoningTokens: Int?,
        cachedTokens: Int?,
        durationMs: Long?,
    ) {
        val now = System.currentTimeMillis()
        val dao = EtaDatabase.get(context.applicationContext).tokenUsageDao()
        runCatching {
            dao.insert(
                TokenUsageRecordEntity(
                    conversationId = conversationId,
                    modelId = modelId,
                    createdAt = now,
                    kind = KIND_USAGE,
                    contextTokens = contextTokens,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                    durationMs = durationMs,
                ),
            )
            dao.deleteBefore(now - RETENTION_MS)
        }
    }

    suspend fun recordDowngrade(context: Context, conversationId: String?, modelId: String?) {
        val now = System.currentTimeMillis()
        val dao = EtaDatabase.get(context.applicationContext).tokenUsageDao()
        runCatching {
            dao.insert(
                TokenUsageRecordEntity(
                    conversationId = conversationId,
                    modelId = modelId,
                    createdAt = now,
                    kind = KIND_DOWNGRADE,
                ),
            )
        }
    }
}
