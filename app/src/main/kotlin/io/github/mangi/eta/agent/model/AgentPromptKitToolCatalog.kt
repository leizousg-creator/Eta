package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.data.promptkit.PromptKitTool

/**
 * 模型目录里的提示词包工具。
 *
 * schema 正文由 PromptKitTool.descriptor() 维护，这里只按现有目录格式包一层，
 * 避免两处描述漂移；提示词正文本身不在这里，也不进任何代码。
 */
internal object AgentPromptKitToolCatalog {
    fun appendTo(tools: JSONArray) {
        val descriptor = PromptKitTool.descriptor()
        tools.put(
            AgentToolSchema.function(
                name = descriptor.getString("name"),
                description = descriptor.getString("description"),
                parameters = descriptor.getJSONObject("parameters"),
            ),
        )
    }
}
