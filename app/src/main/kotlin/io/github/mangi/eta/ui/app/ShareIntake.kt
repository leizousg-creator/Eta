package io.github.mangi.eta.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 系统分享面板送进来的内容暂存区。
 *
 * 别的应用里点「分享 → Eta」后，由 MainActivity 解析 intent 放进这里；界面就绪（AgentAppRoot 挂载）
 * 或应用已经在前台时，通过观察 [version] 取走，填进当前会话的输入框并附图，避免"分享了却不知道去哪了"。
 */
internal object ShareIntake {

    data class Payload(val text: String?, val images: List<String>)

    private val lock = Any()
    private var text: String? = null
    private val images = mutableListOf<String>()

    /** 版本号：Compose 侧观察它来决定何时取走内容。 */
    var version by mutableIntStateOf(0)
        private set

    fun offer(text: String?, imageUris: List<String>) {
        val cleanText = text?.trim().orEmpty()
        val cleanImages = imageUris.filter { it.isNotBlank() }
        if (cleanText.isBlank() && cleanImages.isEmpty()) return
        synchronized(lock) {
            if (cleanText.isNotBlank()) {
                val existing = this.text
                this.text = if (existing.isNullOrBlank()) cleanText else existing + "\n" + cleanText
            }
            this.images += cleanImages
        }
        version += 1
    }

    /** 取走并清空；没有内容时返回 null。 */
    fun consume(): Payload? = synchronized(lock) {
        if (text.isNullOrBlank() && images.isEmpty()) return null
        val payload = Payload(text?.takeIf { it.isNotBlank() }, images.toList())
        text = null
        images.clear()
        payload
    }
}
