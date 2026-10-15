package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeInFlightEventEntity
import io.github.mangi.eta.data.db.RuntimeInFlightRunEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 在途 run 的 UI 事件、完整脱敏 transcript 与模型上下文快照。
 * 三者用途独立；配置和密钥不落盘，敏感工具原始数据由会话 codec 过滤。
 */
internal object AgentRunCheckpointStore {
    /** 每个 run 的父行副本：并发路径把父行清掉时用它补写，避免在途事件外键失败。 */
    private val seededRuns =
        java.util.concurrent.ConcurrentHashMap<String, RuntimeInFlightRunEntity>()
    data class Checkpoint(
        val runId: String,
        val ownerInstanceId: String,
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val createdAt: Long,
        val updatedAt: Long,
        val contextSnapshot: AgentContextSnapshot? = null,
        val operation: String = AgentRuntimeWire.OP_CHAT,
        val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
        val rewriteTargetMessageId: String? = null,
    )

    fun start(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        ownerInstanceId: String = AgentRuntimeProcessIdentity.id,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val handoff = request.handoff ?: return false
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return false
        val runId = request.runId.takeIf(String::isNotBlank) ?: return false
        val entity = RuntimeInFlightRunEntity(
            runId = runId,
            ownerInstanceId = ownerInstanceId,
            operation = request.operation,
            rewriteTargetMessageId = request.rewriteTargetMessageId,
            handoffId = handoff.id,
            handoffSource = handoff.source,
            handoffPayload = handoff.payload,
            dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
            createdAt = now,
            updatedAt = now,
        )
        seededRuns[runId] = entity
        io.github.mangi.eta.core.EtaTrace.log(
            "ckpt.start",
            "runId=$runId operation=${request.operation}",
        )
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao().replaceInFlightRun(entity)
        }
        return true
    }

    fun append(
        context: Context,
        runId: String,
        sortIndex: Int,
        event: AgentEvent,
        now: Long = System.currentTimeMillis(),
    ) {
        val dao = EtaDatabase.get(context.applicationContext).runtimeRunDao()
        val row = RuntimeInFlightEventEntity(
            runId = runId,
            sortIndex = sortIndex,
            eventJson = AgentEventJsonCodec.encode(event),
        )
        try {
            runBlocking(Dispatchers.IO) { dao.appendInFlightEvent(row, now) }
        } catch (constraint: android.database.sqlite.SQLiteConstraintException) {
            // 并发会话下父行可能被其它路径清掉；外键失败会让失败现场整条丢失，
            // 这里先把父行补回来（不动已写入的事件），再写一次。
            io.github.mangi.eta.core.EtaTrace.log(
                "ckpt.recover",
                "runId=$runId sortIndex=$sortIndex 父行缺失已补写：${constraint.message}",
            )
            val seed = seededRuns[runId] ?: throw constraint
            runBlocking(Dispatchers.IO) {
                dao.upsertInFlightRun(seed)
                dao.appendInFlightEvent(row, now)
            }
        }
    }

    /** 返回所有未确认 run；是否 active 或已完成由恢复协调器结合 Runtime 状态判断。 */
    fun list(context: Context): List<Checkpoint> =
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .inFlightRuns()
                .asSequence()
                .map { stored ->
                    Checkpoint(
                        runId = stored.run.runId,
                        ownerInstanceId = stored.run.ownerInstanceId,
                        contextSnapshot = AgentContextSnapshot.decode(stored.run.contextSnapshotJson),
                        operation = stored.run.operation,
                        rewriteTargetMessageId = stored.run.rewriteTargetMessageId,
                        transcript = AgentToolBatchRecovery.completeInterrupted(AgentConversationCodec.decodeTranscript(stored.run.transcriptJson)),
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = stored.run.handoffId,
                            source = stored.run.handoffSource,
                            payload = stored.run.handoffPayload,
                            dismissEntrySurfaceOnForegroundOperation =
                                stored.run.dismissEntrySurface,
                        ),
                        events = stored.events
                            .sortedBy { it.sortIndex }
                            .mapNotNull { AgentEventJsonCodec.decode(it.eventJson) },
                        createdAt = stored.run.createdAt,
                        updatedAt = stored.run.updatedAt,
                    )
                }
                .toList()
        }

    fun saveTranscript(context: Context, runId: String, transcript: List<AgentModelClient.ConversationMessage>) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao()
                .updateTranscript(runId, AgentConversationCodec.encodeTranscriptForStorage(transcript))
        }
    }

    fun saveContext(context: Context, runId: String, snapshot: AgentContextSnapshot) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao().updateContextSnapshot(runId, snapshot.encode())
        }
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        io.github.mangi.eta.core.EtaTrace.log(
            "ckpt.remove",
            "runId=$runId 调用方=${io.github.mangi.eta.core.EtaTrace.callerHint()}",
        )
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .deleteInFlightRun(runId)
        }
    }
}
