package io.github.mangi.eta.ui.components

/**
 * 各会话未发送的输入草稿。
 *
 * 刻意用普通 Map 而不是 Compose 状态：聊天输入每敲一个字符都刷新整个舞台代价太高，
 * 这里只记录、不通知；切回会话时由输入框自己按键读回来。只存在内存里，进程结束即清空。
 */
internal object AgentInputDrafts {
    private val drafts = mutableMapOf<String, String>()

    fun get(key: String): String = drafts[key].orEmpty()

    fun set(key: String, text: String) {
        if (text.isEmpty()) {
            drafts.remove(key)
        } else {
            drafts[key] = text
        }
    }
}
