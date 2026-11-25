package io.github.mangi.eta.agent.approval

import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import org.json.JSONObject

/**
 * 在工具执行前插入审批闸门。
 *
 * 装饰在 [io.github.mangi.eta.agent.mcp.RoutingToolExecutor] 外层：
 * 一次判断决定是否放行，放行后原样转交，拒绝与超时都不执行工具本体。
 *
 * 只拦「需要询问」的调用。完全允许档位下这里退化成直通，不产生任何额外开销。
 */
internal class ApprovalToolExecutor(
    private val delegate: AgentModelClient.ToolExecutor,
    private val runId: String,
    private val isCancelled: () -> Boolean,
) : AgentModelClient.ToolExecutor, AutoCloseable {

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val decision = AgentApprovalPolicy.decide(toolCall.name, toolCall.argumentsJson)
        if (decision == AgentApprovalPolicy.Decision.ALLOW) {
            return delegate.execute(toolCall)
        }
        val settings = AgentApprovalPolicy.current()
        val answer = AgentApprovalBroker.awaitDecision(
            runId = runId,
            toolName = toolCall.name,
            argsPreview = toolCall.argumentsJson,
            risk = AgentApprovalPolicy.describeRisk(toolCall.name),
            timeoutMillis = settings.waitMillis.toLong(),
            isCancelled = isCancelled,
        )
        return when (answer) {
            AgentApprovalBroker.Answer.APPROVED -> delegate.execute(toolCall)
            AgentApprovalBroker.Answer.DENIED -> rejected(
                toolCall = toolCall,
                code = "USER_DENIED_TOOL",
                message = "用户拒绝了这个操作；本次调用未执行。请换一种方式继续，或直接说明这一步被拦下了，不要重复同样的调用。",
                includeImages = false,
            )

            AgentApprovalBroker.Answer.NOT_ANSWERED -> rejected(
                toolCall = toolCall,
                code = "APPROVAL_NOT_ANSWERED",
                message = "等待确认超时，没有人应答；本次调用未执行。不要原样重试，换一种方式继续或说明被拦下。",
                includeImages = false,
            )

            AgentApprovalBroker.Answer.CANCELLED -> throw AgentRunCancelledException()
        }
    }

    private fun rejected(
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
        includeImages: Boolean,
    ): AgentModelClient.ToolResult = AgentModelClient.ToolResult(
        content = JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString(),
        sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
    )

    override fun toString(): String = "ApprovalToolExecutor(run=$runId)"

    /**
     * 关闭不在这里做：run 的 toolsBinding 已绑定在 delegate（RoutingToolExecutor）上，
     * 这里再关一次会把 MCP 连接关两遍。
     */
    override fun close() = Unit
}
