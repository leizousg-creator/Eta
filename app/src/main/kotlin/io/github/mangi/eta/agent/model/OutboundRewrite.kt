package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OutboundRewriteRule
import io.github.mangi.eta.data.model.OutboundRewriteSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 出站改写引擎：把请求里的正文按规则替换，返回新数组，不动原始历史。
 *
 * 边界：
 *  - 只改 user / assistant /（可选）system 的文本，**不碰 tool 结果**——
 *    工具输出里是命令、路径和代码，替换会直接改坏语义；
 *  - 先整包检查是否含任一命中词，没有命中就原样返回同一个数组，禁用时零开销；
 *  - content 既可能是字符串，也可能是块数组（Anthropic / Responses），两种都处理。
 */
internal object OutboundRewrite {

    fun rewrite(messages: JSONArray, settings: OutboundRewriteSettings): JSONArray {
        val rules = settings.activeRules
        if (rules.isEmpty()) return messages
        val haystack = messages.toString()
        if (rules.none { haystack.contains(it.from) }) return messages

        val rewritten = JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index)
            if (message == null) {
                rewritten.put(messages.opt(index))
                continue
            }
            val role = message.optString("role")
            val applies = role == "user" || role == "assistant" ||
                (settings.applyToSystem && role == "system") ||
                (settings.applyToSystem && role == "developer")
            if (!applies) {
                rewritten.put(message)
                continue
            }
            val content = message.opt("content")
            val replaced = when (content) {
                is String -> rewriteText(content, rules)
                is JSONArray -> rewriteBlocks(content, rules)
                else -> null
            }
            if (replaced == null || replaced == content) {
                rewritten.put(message)
                continue
            }
            val copy = JSONObject(message.toString())
            copy.put("content", replaced)
            rewritten.put(copy)
        }
        return rewritten
    }

    private fun rewriteBlocks(blocks: JSONArray, rules: List<OutboundRewriteRule>): JSONArray {
        val out = JSONArray()
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index)
            val text = block?.optString("text").orEmpty()
            if (block == null || text.isEmpty()) {
                out.put(blocks.opt(index))
                continue
            }
            val replaced = rewriteText(text, rules)
            if (replaced == text) {
                out.put(block)
                continue
            }
            val copy = JSONObject(block.toString())
            copy.put("text", replaced)
            out.put(copy)
        }
        return out
    }

    private fun rewriteText(text: String, rules: List<OutboundRewriteRule>): String {
        var result = text
        for (rule in rules) {
            if (rule.from.isEmpty()) continue
            result = result.replace(rule.from, rule.to)
        }
        return result
    }
}
