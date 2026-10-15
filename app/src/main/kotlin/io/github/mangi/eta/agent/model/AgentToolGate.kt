package io.github.mangi.eta.agent.model

import java.util.concurrent.locks.ReentrantLock

/**
 * 多会话并发时的工具串行闸门。
 *
 * 手机只有一块屏幕、一个终端环境、一套文件系统：两个会话同时点界面或跑命令会互相抢，
 * 表现为点击落到别人打开的页面、命令输出串台。这里让「会动手机或系统状态」的工具
 * 全局排队（公平锁，先到先得），只放行纯读取类工具，
 * 这样并发会话仍能并行看文件、查信息，只有真正独占资源的操作才等待。
 */
internal object AgentToolGate {
    private val lock = ReentrantLock(true)

    /** 纯只读工具：不改变手机、终端或文件状态，可以并发执行。 */
    private val readOnlyTools = setOf(
        "read_file",
        "stat_file",
        "list_directory",
        "glob_files",
        "grep_files",
    )

    /** 只读类工具的前缀：查询、读取类一律放行。 */
    private val readOnlyPrefixes = listOf(
        "get_",
        "search_",
        "recent_",
        "read_",
        "list_",
        "memory_get",
        "skills_list",
        "skills_read",
        "observe_",
    )

    /** 这个工具是否需要独占（会动手机/系统状态）。 */
    fun requiresGate(toolName: String): Boolean {
        val name = toolName.trim()
        if (name.isEmpty()) return true
        if (name in readOnlyTools) return false
        return readOnlyPrefixes.none { name.startsWith(it) }
    }

    /** 需要独占的工具排队执行；只读工具直接放行。 */
    fun <T> withGate(toolName: String, block: () -> T): T {
        if (!requiresGate(toolName)) return block()
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
