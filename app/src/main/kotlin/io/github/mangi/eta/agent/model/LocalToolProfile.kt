package io.github.mangi.eta.agent.model

/**
 * 「工具档」：控制发给模型的工具 schema 与系统提示体量。
 * - 正常档为原样发送（默认）；
 * - 纯聊天档完全不声明工具，适合只支持对话的模型；
 * - 其余三档用于适配上下文较小的本地模型。
 * 只影响请求内容，不影响工具自身的执行能力。
 */
internal enum class LocalToolProfile(val id: String) {
    /** 正常：工具与描述原样发送，不做任何压缩。 */
    FULL("full"),

    /** 开发：只留开发链路工具（终端／文件／GUI／日志／记忆），手机个人数据类工具不发送。 */
    DEV("dev"),

    /** 纯聊天：不发送任何工具定义，只做对话（仍可接收图片）。 */
    CHAT("chat"),

    /** 轻度：工具全留，只压缩描述文字。 */
    LIGHT("light"),

    /** 标准：只留核心工具，设备/技能/任务/浏览器等长尾工具不发送。 */
    STANDARD("standard"),

    /** 极简：只留最小工具集，最省 token。 */
    MINIMAL("minimal");

    /** 自动降档顺序：正常 → 轻度 → 标准 → 极简；开发档降到标准档仍保留终端／文件。纯聊天档不带工具，无需降档。 */
    fun leaner(): LocalToolProfile? = when (this) {
        FULL -> LIGHT
        DEV -> STANDARD
        LIGHT -> STANDARD
        STANDARD -> MINIMAL
        MINIMAL, CHAT -> null
    }

    companion object {
        fun fromId(id: String?): LocalToolProfile =
            values().firstOrNull { it.id == id } ?: FULL
    }
}
