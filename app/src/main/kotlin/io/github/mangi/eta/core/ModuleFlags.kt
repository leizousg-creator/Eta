package io.github.mangi.eta.core

/**
 * 我们自建模块的运行时开关。
 *
 * 为什么需要：模组（提示词包 `prompt_kit`、vivo 个人数据工具）会改变工具目录与系统提示词的内容，
 * 而上游的单测是按「没有这些工具」写死的断言。与其改掉几十个上游测试，
 * 不如让这些模块只在真实应用进程里启用：`EtaApp.onCreate` 打开，单元测试不经过 Application，
 * 自动保持上游行为。
 */
object ModuleFlags {
    /** 工具类模块（`prompt_kit`、vivo 个人数据）是否注入工具目录。 */
    @Volatile
    var toolsEnabled: Boolean = false

    /**
     * `batch` 工具是否对外暴露。
     *
     * 目前只实现了目录投影（`AgentBatchToolCatalog.project`），执行侧尚未接入 ——
     * 模型一旦调用会落到「未知工具」。默认保持开启只为与既有单测的目录断言一致；
     * 真正要用之前必须先补 `AgentLocalTools` 里的 batch 执行分支。
     */
    @Volatile
    var batchExecutionEnabled: Boolean = true
}
