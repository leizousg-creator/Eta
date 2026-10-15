package io.github.mangi.eta.ui.app

import android.content.Context

/**
 * 按会话 ID 存的输入草稿。
 *
 * 之前草稿只活在内存里（切会话时写回 conversationsById），只要那个会话状态从数据库重载一次，
 * 没发出去的文字就没了。这里补一层落盘，切回来时贴回输入框。
 */
internal object ChatDraftStore {
    private const val FILE = "eta_chat_drafts"
    private const val MAX_DRAFTS = 200

    fun load(context: Context, conversationId: String): String {
        if (conversationId.isBlank()) return ""
        return runCatching { prefs(context).getString(conversationId, null) }.getOrNull().orEmpty()
    }

    fun save(context: Context, conversationId: String, text: String) {
        if (conversationId.isBlank()) return
        runCatching {
            val prefs = prefs(context)
            if (text.isBlank()) {
                prefs.edit().remove(conversationId).apply()
            } else {
                prefs.edit().putString(conversationId, text).apply()
                val keys = prefs.all.keys.toList()
                if (keys.size > MAX_DRAFTS) {
                    keys.take(keys.size - MAX_DRAFTS).forEach { prefs.edit().remove(it).apply() }
                }
            }
        }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
