package io.github.mangi.eta.core

/**
 * 我们自建模块的运行时开关。
 *
 * 为什么需要：模组（自动任务 `tasks_*`、提示词包 `prompt_kit`、工作准则）会改变
 * 工具目录与系统提示词的内容，而上游的单测是按「没有这些工具」写死的断言。
 * 与其改掉几十个上游测试，不如让这些模块只在真实应用进程里启用：
 * `EtaApp.onCreate` 打开，单元测试不经过 Application，自动保持上游行为。
 */
object ModuleFlags {
    /** 工具类模块（tasks_*、prompt_kit）是否注入工具目录。 */
    @Volatile
    var toolsEnabled: Boolean = false

    /**
     * 批量工具（`batch`）是否对外暴露。
     * 工具目录投影已迁入，但执行侧（AgentLoop 的分段/校验/并发波次）尚未接线，
     * 在接线完成前保持关闭，避免模型看到一个执行不了的工具。
     */
    @Volatile
    var batchExecutionEnabled: Boolean = false
}
