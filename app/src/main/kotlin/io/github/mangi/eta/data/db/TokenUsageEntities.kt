package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 模型用量记录：一次请求一行（kind = usage），降档重试另记一行（kind = downgrade）。
 * 只服务设置里的用量统计，不参与会话内容，可整表清理。
 */
@Entity(tableName = "token_usage_records", indices = [Index("created_at")])
internal data class TokenUsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val kind: String,
    @ColumnInfo(name = "context_tokens") val contextTokens: Int? = null,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Int? = null,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Int? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
)

@Dao
internal interface TokenUsageDao {
    @Insert
    suspend fun insert(record: TokenUsageRecordEntity)

    @Query("SELECT * FROM token_usage_records WHERE created_at >= :since ORDER BY created_at DESC")
    suspend fun recordsSince(since: Long): List<TokenUsageRecordEntity>

    @Query("DELETE FROM token_usage_records WHERE created_at < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("SELECT COUNT(*) FROM token_usage_records")
    suspend fun recordCount(): Int

    @Query("SELECT MIN(created_at) FROM token_usage_records")
    suspend fun earliestCreatedAt(): Long?

    @Query("DELETE FROM token_usage_records")
    suspend fun deleteAll(): Int
}
