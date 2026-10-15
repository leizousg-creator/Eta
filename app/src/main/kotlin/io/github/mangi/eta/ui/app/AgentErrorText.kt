package io.github.mangi.eta.ui.app

/**
 * 运行错误码 → 中文提示。
 *
 * 只翻"给人看的那一层"：错误码仍旧原样写进自留日志和工具结果，模型与内部逻辑照旧按码判断，
 * 界面上只出中文。所以这里**只**处理会话级失败文案，不碰 INVALID_ARGUMENT、ROOT_REQUIRED 这类
 * 工具层稳定标识。
 *
 * 规则：
 * 1. 空 → 用调用方给的兜底文案；
 * 2. 已经是中文（含汉字）→ 原样返回，不重复加工；
 * 3. 认识的码 → 对应中文；
 * 4. 不认识的英文码 → "运行出错，请重试。（代码：XXX）"，中文在前、代码在后，方便回查也能看懂。
 */
internal object AgentErrorText {

    const val DEFAULT_FAILURE = "运行出错，请重试。"

    fun toChinese(raw: String?, fallback: String = DEFAULT_FAILURE): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return fallback
        if (text.any { char -> char in '\u4e00'..'\u9fff' }) return text
        known[text.uppercase()]?.let { return it }
        if (text.uppercase().startsWith("MODEL_")) {
            return "模型请求失败，请检查网络或模型配置后重试。（代码：$text）"
        }
        if (text.length <= 3) return fallback
        return "运行出错，请重试。（代码：$text）"
    }

    private val known = mapOf(
        "RUNTIME_CONCURRENCY_LIMIT" to
            "同时运行的会话已达上限，本条没有执行；等有会话跑完会自动开始，也可以点「继续」重试。",
        "RUN_REJECTED" to "本条没有执行，请稍后重试。",
        "AUTOMATION_RUNTIME_BUSY" to "已有会话在运行，这条自动任务这次没有执行，稍后会自动重试。",
        "AUTOMATION_RUNTIME_UNAVAILABLE" to "运行时还没准备好，这条自动任务没有执行，稍后会自动重试。",
        "RUNTIME_BUSY" to "运行时正忙，本条没有执行，请稍后重试。",
        "CANCELLED" to "已停止。",
        "CANCELED" to "已停止。",
        "TASK_CANCELLED" to "已停止。",
        "AGENT RUN CANCELLED" to "已停止。",
        "AGENT RUN STOPPED" to "已停止。",
        "RUN_STOPPED" to "已停止。",
        "SERVICE_TIMEOUT" to "等待服务响应超时，请重试。",
        "RUNTIME_TIMEOUT" to "运行超时，请重试。",
        "MODEL_HTTP_ERROR" to "模型服务返回错误，请检查网络或模型配置后重试。",
        "MODEL_TIMEOUT" to "模型响应超时，请重试。",
        "MODEL_AUTH_FAILED" to "模型服务鉴权失败，请检查密钥配置。",
        "MODEL_NOT_CONFIGURED" to "还没有配置可用的模型，请先到设置里选一个。",
        "CONTEXT_LIMIT_EXCEEDED" to "上下文超出模型上限，已尝试压缩，请重试或新开会话。",
        "INVALID_JSON" to "返回内容格式不对，请重试。",
        "NETWORK_UNAVAILABLE" to "当前没有网络，请连上网后重试。",
        "STORAGE_FULL" to "存储空间不足，请清理后重试。",
    )
}
