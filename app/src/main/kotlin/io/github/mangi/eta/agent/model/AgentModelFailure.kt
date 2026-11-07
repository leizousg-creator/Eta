package io.github.mangi.eta.agent.model

import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Provider 边界只分类失败；重试预算与上下文由 Loop 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
            "RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED",
        )

        private val invalidApiKeyReasons = setOf("API_KEY_INVALID", "API_KEY_EXPIRED")

        fun http(status: Int, body: String, includeParameterDetails: Boolean = false): AgentModelFailure {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "模型上下文超过容量限制。",
            )
            val permanent = isPermanent(error, body)
            if (status == 400 && includeParameterDetails && !permanent) {
                ProviderParameterDiagnostics.describe(error)?.let { details ->
                    return AgentModelFailure("HTTP_400", false, "模型请求参数无效（HTTP 400；$details），请检查模型配置。")
                }
            }
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                message = if (permanent) "模型接口额度或计费受限（HTTP $status），请检查服务商账户。"
                else when (status) {
                    400 -> "模型请求参数无效（HTTP 400），请检查模型配置。"
                    401 -> "模型接口认证失败（HTTP 401），请检查 API Key。"
                    403 -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                    404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                    429 -> "模型接口暂时限流（HTTP 429）。"
                    else -> "模型接口返回 HTTP $status"
                },
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "模型上下文超过容量限制。",
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optString("status"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        /**
         * 传输层失败分类。
         *
         * 顺序有意义：SocketTimeoutException 属于 InterruptedIOException，ConnectException 属于
         * SocketException，SSLException / UnknownHostException / EOFException 都继承 IOException，
         * 必须从具体到宽泛依次判断，否则会被更宽的分支吞掉。
         */
        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is SocketTimeoutException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求超时（等待响应超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            is SSLException -> AgentModelFailure(
                "MODEL_TLS_FAILED", true,
                "SSL 握手失败：证书校验未通过或被中间设备拦截，请确认接口使用 https、代理未解密 TLS。",
                failure,
            )
            is UnknownHostException -> AgentModelFailure(
                "MODEL_DNS_FAILED", true,
                "DNS 解析失败：域名无法解析，请检查接口地址是否写错，或当前网络/代理的 DNS 是否可用。",
                failure,
            )
            is ConnectException -> AgentModelFailure(
                "MODEL_CONNECT_REFUSED", true,
                "连接被拒绝：目标地址没有服务在监听，请检查供应商地址与端口。",
                failure,
            )
            is SocketException -> AgentModelFailure(
                "MODEL_CONNECTION_RESET", true,
                "连接被中断：对端重置或网络切换，常见于代理不稳定或服务端主动断开。",
                failure,
            )
            is EOFException -> AgentModelFailure(
                "MODEL_STREAM_EOF", true,
                "连接被对端关闭：响应未读完就结束，可能是服务端超时或代理中断。",
                failure,
            )
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> null
        }

        /** 重试气泡里展示的短原因，按 [code] 归类；未收录的退回「网络异常」。 */
        fun reasonText(code: String): String = when (code) {
            "MODEL_TIMEOUT" -> "等待响应超时"
            "MODEL_TLS_FAILED" -> "SSL 握手失败"
            "MODEL_DNS_FAILED" -> "DNS 解析失败"
            "MODEL_CONNECT_REFUSED" -> "连接被拒绝"
            "MODEL_CONNECTION_RESET" -> "连接被中断"
            "MODEL_STREAM_EOF" -> "服务端提前断开"
            "MODEL_CONNECTION_FAILED" -> "网络连接中断"
            "STREAM_INCOMPLETE" -> "响应流未完整"
            "HTTP_408" -> "服务端超时"
            "HTTP_429" -> "触发速率限制"
            "HTTP_500", "HTTP_502", "HTTP_504", "HTTP_524", "HTTP_529" -> "服务端异常"
            "HTTP_503" -> "服务端负载过高"
            "PROVIDER_STREAM_ERROR" -> "服务端返回错误流"
            else -> if (code.startsWith("HTTP_")) "HTTP ${code.removePrefix("HTTP_")}" else "网络异常"
        }


        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("input token count exceeds") ||
                message.contains("input token count") && message.contains("exceeds the maximum")
        }

        private fun authenticationFailure(error: JSONObject?): AgentModelFailure? {
            if (error == null) return null
            val details = error.optJSONArray("details")
            val invalidKey = (0 until (details?.length() ?: 0)).any { index ->
                details?.optJSONObject(index)?.optString("reason") in invalidApiKeyReasons
            }
            return if (invalidKey || error.optString("status") == "UNAUTHENTICATED") {
                AgentModelFailure("PROVIDER_AUTH_FAILED", false, "模型接口认证失败，请检查 API Key。")
            } else null
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
