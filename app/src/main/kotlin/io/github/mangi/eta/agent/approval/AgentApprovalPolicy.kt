package io.github.mangi.eta.agent.approval

import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.data.model.AgentApprovalMode
import io.github.mangi.eta.data.model.AgentApprovalSettings

/**
 * 审批规则：给定工具名与参数，判断要不要打断用户。
 *
 * 规则来源是上游三档语义。AUTO_APPROVE 的白名单来自上游原文：
 * 屏幕点击滑动、读 UI 节点、只读命令、网络取内容、搜索、读剪贴板、交给其它应用、写改文件都放行；
 * 只在四条风险线上询问：改系统设置或设备状态、读联系人短信等个人信息、跨屏搬任务、以用户名义发消息。
 *
 * 第一版刻意保守：命令执行不做「只读命令」内容分析，AUTO 下一律询问。
 * 命令参数的自由度太大，靠首词白名单判断只读很容易漏（管道、重定向、`$(...)`、别名），
 * 漏判的代价是静默写入。等审批 UI 稳定后再补内容级放行。
 */
internal object AgentApprovalPolicy {

    enum class Decision {
        /** 直接执行。 */
        ALLOW,

        /** 停下等待用户确认。 */
        ASK,
    }

    @Volatile
    private var settings: AgentApprovalSettings = AgentApprovalSettings()

    fun publish(next: AgentApprovalSettings) {
        settings = next.normalized()
    }

    fun current(): AgentApprovalSettings = settings

    fun decide(toolName: String, argsJson: String): Decision {
        val mode = settings.mode
        return when (mode) {
            AgentApprovalMode.FULL_ACCESS -> Decision.ALLOW
            AgentApprovalMode.ASK_EVERY -> Decision.ASK
            AgentApprovalMode.AUTO_APPROVE ->
                if (isRiskTool(toolName) || isEvalCall(toolName, argsJson)) Decision.ASK else Decision.ALLOW
        }
    }

    /**
     * `browser_devtools` 只有 `eval` 需要拦：它能执行任意脚本，等价于在页面里跑命令，
     * 读 console/network 只是回读已经发生的事，不必打断。
     */
    private fun isEvalCall(toolName: String, argsJson: String): Boolean {
        if (toolName != "browser_devtools") return false
        val action = runCatching { org.json.JSONObject(argsJson).optString("action") }.getOrDefault("")
        return action.equals("eval", ignoreCase = true)
    }

    /** AUTO_APPROVE 下的风险线。命中即为需要打断。 */
    fun isRiskTool(toolName: String): Boolean {
        if (toolName.startsWith(MCP_PREFIX)) return true
        if (toolName in personalTools) return true
        if (toolName in deviceWriteTools) return true
        if (toolName in commandTools) return true
        if (toolName in outboundTools) return true
        return false
    }

    fun describeRisk(toolName: String): String = when {
        toolName.startsWith(MCP_PREFIX) -> "MCP 工具"
        toolName in personalTools -> "读取个人信息"
        toolName in deviceWriteTools -> "修改系统设置或设备状态"
        toolName in commandTools -> "执行命令"
        toolName in outboundTools -> "以你的名义对外操作"
        else -> "敏感操作"
    }

    private const val MCP_PREFIX = "mcp_"

    /** 读联系人、短信、剪贴板、定位、健康、相册、日历、日志等个人数据。 */
    private val personalTools = setOf(
        "search_messages",
        "read_sms_code",
        "search_contacts",
        "search_call_history",
        "search_clipboard_history",
        "get_health_summary",
        "get_current_location",
        "get_current_context",
        "recent_notifications",
        "search_notification_history",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_downloads",
        "search_calendar_events",
        "search_notes",
        "read_note",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_system_memories",
        "search_saved_places",
        "search_qq_chat_images",
        "search_wechat_chat_images",
        "search_personal_orders",
        "read_personal_item",
        "recent_app_activity",
        "app_usage_summary",
        "wifi_credentials",
        "get_logcat",
        "conversation_history",
        "summarize_bills",
    )

    /** 改系统设置或设备状态。 */
    private val deviceWriteTools = setOf(
        "set_setting",
        "set_device_state",
        "set_brightness",
        "set_screen_timeout",
        "set_volume",
        "set_sound_mode",
        "set_do_not_disturb",
        "set_flashlight",
        "set_hotspot",
        "app_state_control",
        "media_control",
    )

    /** 命令执行一律询问，见类注释。 */
    private val commandTools = setOf(
        "terminal",
        "terminal_job",
        "terminal_daemon",
    )

    /** 以用户名义对外操作、或把任务搬到别的屏。 */
    private val outboundTools = setOf(
        "present_to_user",
        "open_uri",
    )

    /** 供自检与提示词生成使用。 */
    fun riskSummary(): String =
        "${personalTools.size + deviceWriteTools.size + commandTools.size + outboundTools.size} 个工具在自动审批下会询问"
}
