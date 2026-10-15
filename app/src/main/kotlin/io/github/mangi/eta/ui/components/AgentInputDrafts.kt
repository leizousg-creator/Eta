package io.github.mangi.eta.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 各会话未发送的输入草稿。
 *
 * 刻意用普通 Map 而不是 Compose 状态：聊天输入每敲一个字符都刷新整个舞台代价太高，
 * 这里只记录、不通知；切回会话时由输入框自己按键读回来。只存在内存里，进程结束即清空。
 *
 * 另有一条**外部注入**通道（分享到 Eta、语音喂话等）：输入框的文本框状态是 rememberTextFieldState，
 * 只在首次组合时读初值，光改业务状态里的 input 是显示不出来的；所以外部内容走这里排队，
 * 由输入框观察 [injectVersion] 主动灌进文本框。
 */
internal object AgentInputDrafts {
    private val drafts = mutableMapOf<String, String>()

    private var pendingInjection: String? = null

    /** 外部注入版本号：输入框观察它来把内容灌进文本框。 */
    var injectVersion by mutableIntStateOf(0)
        private set

    fun get(key: String): String = drafts[key].orEmpty()

    fun set(key: String, text: String) {
        if (text.isEmpty()) {
            drafts.remove(key)
        } else {
            drafts[key] = text
        }
    }

    /** 外部往输入框塞文字：不需要知道草稿键，排队等输入框取走。 */
    fun inject(text: String) {
        if (text.isBlank()) return
        val existing = pendingInjection
        pendingInjection = if (existing.isNullOrBlank()) text else existing.trimEnd() + "\n" + text
        injectVersion += 1
    }

    /** 输入框取走待注入内容（取走后清空）。 */
    fun takeInjection(): String? {
        val value = pendingInjection
        pendingInjection = null
        return value
    }
}
