package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject

/** 旧普通身份入口复用文件工具合同，保持调用方的路径错误码。 */
internal object UserFileAccess {
    fun backend(cwd: String = TerminalRuntime.userWorkspacePath, isCancelled: () -> Boolean = { false }): FileToolBackend {
        val workspace = File(TerminalRuntime.userWorkspacePath)
        // 上游 LocalFileToolBackend 只接受 workspace/home；allowedRoots 是自建线的额外约束，
        // 这里以上游签名为准，路径范围由 workspace 自身限定。
        return LocalFileToolBackend(
            workspace = File(cwd),
            home = workspace,
            isCancelled = isCancelled,
        )
    }

    private fun operations(): AgentFileOperations = AgentFileOperations(backend())

    fun read(path: String, offsetBytes: Int, maxBytes: Int): String =
        legacyResult(operations().readFile(path, offsetBytes.toLong(), maxBytes, maxLines = 2000))

    fun write(path: String, content: String, append: Boolean): String =
        legacyResult(operations().writeFile(path, content, append))

    fun list(path: String, showHidden: Boolean, limit: Int): String =
        legacyResult(operations().listDirectory(path, showHidden, limit))

    private fun legacyResult(result: String): String {
        val json = JSONObject(result)
        if (json.optString("code") == "PATH_OUTSIDE_SCOPE") json.put("code", "INVALID_PATH")
        return json.toString()
    }
}
