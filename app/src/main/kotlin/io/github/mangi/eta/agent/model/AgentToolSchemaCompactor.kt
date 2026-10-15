package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 压缩工具 schema 里的自然语言描述，保留 type／enum／required 等结构信息。
 * 只改发给模型的内容，不影响工具校验与执行；「正常」档完全不压缩。
 */
internal object AgentToolSchemaCompactor {
    fun compact(tools: JSONArray, profile: LocalToolProfile) {
        if (profile == LocalToolProfile.FULL) return
        val functionLimit = functionLimit(profile)
        val propertyLimit = propertyLimit(profile)
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            shorten(function, functionLimit)
            compactNode(function.optJSONObject("parameters"), propertyLimit)
        }
    }

    private fun functionLimit(profile: LocalToolProfile): Int = when (profile) {
        LocalToolProfile.CHAT, LocalToolProfile.MINIMAL -> 90
        LocalToolProfile.STANDARD -> 150
        LocalToolProfile.DEV, LocalToolProfile.LIGHT, LocalToolProfile.FULL -> 260
    }

    private fun propertyLimit(profile: LocalToolProfile): Int = when (profile) {
        LocalToolProfile.CHAT, LocalToolProfile.MINIMAL -> 55
        LocalToolProfile.STANDARD -> 80
        LocalToolProfile.DEV, LocalToolProfile.LIGHT, LocalToolProfile.FULL -> 110
    }

    private fun compactNode(node: JSONObject?, limit: Int) {
        if (node == null) return
        node.optJSONObject("properties")?.let { properties ->
            val keys = properties.keys()
            while (keys.hasNext()) {
                val child = properties.optJSONObject(keys.next()) ?: continue
                shorten(child, limit)
                compactNode(child, limit)
            }
        }
        node.optJSONObject("items")?.let { items ->
            shorten(items, limit)
            compactNode(items, limit)
        }
        for (key in COMBINATORS) {
            val list = node.optJSONArray(key) ?: continue
            for (index in 0 until list.length()) {
                val child = list.optJSONObject(index) ?: continue
                shorten(child, limit)
                compactNode(child, limit)
            }
        }
    }

    private fun shorten(node: JSONObject, limit: Int) {
        if (!node.has("description")) return
        val text = node.optString("description")
        if (text.length > limit) node.put("description", text.take(limit).trimEnd() + "…")
    }

    private val COMBINATORS = listOf("anyOf", "oneOf", "allOf")
}
