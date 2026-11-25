package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "操作 Eta 共享的离屏 Agent 浏览器，不会切换到外部浏览器。一次调用只执行一个 action；网页浏览通常先 navigate，再用 get_readable 提取正文，或用 find_elements 查找可交互元素。需要把 URI 显式交给外部应用时使用 open_uri。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "本次唯一执行的浏览器动作。")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("navigate")
                                            .put("get_readable")
                                            .put("get_text")
                                            .put("find_elements")
                                            .put("click")
                                            .put("type")
                                            .put("scroll")
                                            .put("screenshot")
                                            .put("get_page_info")
                                            .put("go_back")
                                            .put("go_forward")
                                            .put("reload")
                                            .put("wait_for_selector")
                                            .put("list_tabs")
                                            .put("close_tab")
                                    )
                            )
                            .put(
                                "url",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "navigate 要访问的 URL。")
                            )
                            .put(
                                "selector",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "click、type、get_text、find_elements 或 wait_for_selector 使用的 CSS selector。")
                            )
                            .put(
                                "text",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "type 要输入的文本。只会发送给工具，不会显示在运行摘要中。")
                            )
                            .put(
                                "submit",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "type 输入后是否提交所在表单，默认 false。")
                            )
                            .put(
                                "coordinate_x",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click 或 type 的视口 X 坐标，和 coordinate_y 一起使用。")
                            )
                            .put(
                                "coordinate_y",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click 或 type 的视口 Y 坐标，和 coordinate_x 一起使用。")
                            )
                            .put(
                                "amount",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "scroll 的滚动像素量。")
                            )
                            .put(
                                "direction",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("up").put("down"))
                                    .put("description", "scroll 的滚动方向。")
                            )
                            .put(
                                "offset",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable 或 get_text 的文本起始偏移，默认 0。")
                            )
                            .put(
                                "max_chars",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable 或 get_text 最多返回的文本字符数。")
                            )
                            .put(
                                "read_image",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "screenshot 时是否把截图附给模型直接查看，默认 true。")
                            )
                            .put(
                                "timeout_ms",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "navigate 或 wait_for_selector 的超时毫秒数。")
                            )
                            .put(
                                "tab",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "标签名，只能字母、数字、-、_，最长 32。省略时用默认标签并一直保留。" +
                                        "用 list_tabs 查看已开标签，close_tab 加 tab 关掉；不同标签各自持有自己的页面与历史。")
                            )
                    )
                    .put("required", JSONArray().put("action"))
            )
        )
        tools.put(
            AgentToolSchema.function(
                name = "browser_devtools",
                description = "读取 Agent 浏览器当前页面的调试信息：console 日志、网络请求、DOM 结构、按 selector 查元素，或执行一段 JavaScript 取回结果。先用 browser_use navigate 到目标页再调用。" +
                    "eval 每次执行都要用户确认；表单值、cookie、localStorage 一律不返回。" +
                    "console 与 network 只记录页面加载后发生的调用，需要早期日志时先 navigate 再立刻调用。" +
                    "返回的日志和请求头已脱敏（token、cookie、密码类字段替换为 [REDACTED]）。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "本次读取的调试维度。")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("console")
                                            .put("network")
                                            .put("dom")
                                            .put("query")
                                            .put("eval")
                                    )
                            )
                            .put(
                                "selector",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "dom 的根节点或 query 的元素匹配式，CSS selector；省略时 dom 取 body、query 取可交互元素。")
                            )
                            .put(
                                "code",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "eval 要执行的 JavaScript 表达式或语句。返回 Promise 时会等它完成。")
                            )
                            .put(
                                "limit",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "console、network、query 最多返回的条目数，默认 50。")
                            )
                            .put(
                                "since_id",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "只看 id 大于该值的条目；用上次返回的 last_id 续读，避免重复。")
                            )
                            .put(
                                "max_nodes",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "dom 最多返回的节点数，默认 200，上限 600。")
                            )
                            .put(
                                "tab",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "读取哪个标签的调试信息；省略时读默认标签。")
                            )
                    )
                    .put("required", JSONArray().put("action"))
            )
        )
    }

}
