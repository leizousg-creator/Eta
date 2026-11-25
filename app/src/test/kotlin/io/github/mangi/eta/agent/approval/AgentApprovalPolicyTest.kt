package io.github.mangi.eta.agent.approval

import io.github.mangi.eta.data.model.AgentApprovalMode
import io.github.mangi.eta.data.model.AgentApprovalSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentApprovalPolicyTest {

    @After
    fun reset() {
        AgentApprovalPolicy.publish(AgentApprovalSettings())
    }

    private fun publish(mode: AgentApprovalMode) {
        AgentApprovalPolicy.publish(AgentApprovalSettings(mode = mode))
    }

    @Test
    fun fullAccessAllowsEverything() {
        publish(AgentApprovalMode.FULL_ACCESS)
        listOf("terminal", "search_messages", "set_setting", "mcp_github", "observe_screen").forEach { name ->
            assertEquals(name, AgentApprovalPolicy.Decision.ALLOW, AgentApprovalPolicy.decide(name, "{}"))
        }
    }

    @Test
    fun askEveryAsksEverything() {
        publish(AgentApprovalMode.ASK_EVERY)
        listOf("terminal", "observe_screen", "fetch_url").forEach { name ->
            assertEquals(name, AgentApprovalPolicy.Decision.ASK, AgentApprovalPolicy.decide(name, "{}"))
        }
    }

    @Test
    fun autoApproveAllowsScreenAndReadTools() {
        publish(AgentApprovalMode.AUTO_APPROVE)
        listOf(
            "observe_screen",
            "tap",
            "swipe",
            "type_text",
            "press_key",
            "fetch_url",
            "web_search",
            "read_file",
            "write_file",
            "edit_file",
            "get_clipboard",
            "browser_use",
            "memory_write",
        ).forEach { name ->
            assertEquals(name, AgentApprovalPolicy.Decision.ALLOW, AgentApprovalPolicy.decide(name, "{}"))
        }
    }

    @Test
    fun autoApproveAsksOnRiskLines() {
        publish(AgentApprovalMode.AUTO_APPROVE)
        // 个人信息
        assertAsk("search_messages")
        assertAsk("search_contacts")
        assertAsk("read_sms_code")
        assertAsk("get_current_location")
        // 设置与设备状态
        assertAsk("set_setting")
        assertAsk("set_device_state")
        assertAsk("set_brightness")
        assertAsk("app_state_control")
        // 命令
        assertAsk("terminal")
        assertAsk("terminal_job")
        // 对外
        assertAsk("present_to_user")
        // MCP
        assertAsk("mcp_github_create_issue")
    }

    @Test
    fun autoApproveKeepsDefaultModeOffTheWay() {
        publish(AgentApprovalMode.FULL_ACCESS)
        assertTrue(AgentApprovalPolicy.current().mode == AgentApprovalMode.FULL_ACCESS)
        assertTrue(!AgentApprovalPolicy.current().asksAnything)
    }

    @Test
    fun autoApproveAsksOnDevtoolsEval() {
        publish(AgentApprovalMode.AUTO_APPROVE)
        // eval 能执行任意脚本，等价命令执行
        assertEquals(
            AgentApprovalPolicy.Decision.ASK,
            AgentApprovalPolicy.decide("browser_devtools", """{"action":"eval","code":"1+1"}"""),
        )
        // 只回读已发生的事，不打断
        assertEquals(
            AgentApprovalPolicy.Decision.ALLOW,
            AgentApprovalPolicy.decide("browser_devtools", """{"action":"console"}"""),
        )
        assertEquals(
            AgentApprovalPolicy.Decision.ALLOW,
            AgentApprovalPolicy.decide("browser_devtools", """{"action":"network","limit":20}"""),
        )
    }

    private fun assertAsk(name: String) {
        assertEquals(name, AgentApprovalPolicy.Decision.ASK, AgentApprovalPolicy.decide(name, "{}"))
    }
}
