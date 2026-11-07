package io.github.mangi.eta.agent.model

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 计划模式：只做只读探索，把改动写成方案，等用户批准后再动手。
 *
 * 和「提示词里劝模型别乱改」不同，这里是**工具层硬拦截**：写类工具在执行入口直接返回错误，
 * 模型只会得到一条「计划模式拒绝执行」的工具结果，绕不过去。
 *
 * 状态只放内存：它是一次会话里的姿态，进程重启回到普通模式，不会在用户不知情时长期生效。
 * （运行 agent 的 AgentRuntimeService 与 UI 同进程，所以静态对象两端共享。）
 */
internal object AgentPlanMode {
    private val state = AtomicBoolean(false)

    val enabled: Boolean get() = state.get()

    fun set(value: Boolean) {
        state.set(value)
    }

    /** 计划模式下允许的只读工具：设备读操作来自工具目录，其余按名字/前缀补。 */
    private val explicitReadOnly = setOf(
        "get_current_context",
        "device_status",
        "network_info",
        "top_memory_apps",
        "top_storage_apps",
        "recent_notifications",
        "inspect_app",
        "observe_screen",
        "web_search",
        "fetch_url",
        "memory_get",
        "skills_read",
        "skills_list",
        "read_sms_code",
    )

    private val readOnlyPrefixes = listOf(
        "read_", "search_", "list_", "get_", "find_", "grep_", "glob_", "stat_",
        "top_", "inspect_", "view_", "query_",
    )

    fun allowsTool(toolName: String): Boolean {
        if (toolName in AgentPhoneToolCatalog.reads) return true
        if (toolName in explicitReadOnly) return true
        return readOnlyPrefixes.any { toolName.startsWith(it) }
    }

    /** 拒绝时返回给模型的说明：既要解释为什么，也要指出下一步该做什么。 */
    fun blockedMessage(toolName: String): String =
        "计划模式只做只读探索：$toolName 会改变设备、文件或配置状态，已在执行入口被拦截。" +
            "把这一步写进方案（要改什么、为什么、怎么验证），等用户关闭计划模式后再执行。"

    /** 追加到系统提示的约束段。 */
    val prompt: String = """
        当前处于计划模式（PLAN）：你只做只读探索与方案设计，不执行任何写操作。

        约束：
        - 终端、文件写入、应用安装与管理、屏幕点击、配置修改类工具会被执行入口直接拦截并返回错误，不要尝试绕过或改写命令。
        - 只用只读工具（读文件、搜索、查设备与页面状态、检索记忆与网络）确认事实；关键结论要来自真实读取，不凭文件名臆测。
        - 被拦下时立刻停手，把需要的那一步写进方案，不要换个工具重试同一件事。

        交付：
        - 方案要具体到文件/设置项：改哪些、每处改什么、为什么，验证方式（跑哪条命令或看哪个界面）。
        - 说明理解与取舍、兼容性风险（数据、权限、提示词、协议）、执行顺序与回退办法。
        - 写完就停下等用户批准；不要一边规划一边动手，也不要在方案里夹带已执行的改动。
    """.trimIndent()
}
