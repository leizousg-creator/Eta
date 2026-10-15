package io.github.mangi.eta.agent.display

import android.content.Context
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * 触屏输入专用通道：单线程串行下发，只在队尾合并连续拖动。
 * 以前每个触摸事件各起一个协程各自抢管道，拖动时几十条请求互相排队，
 * 手感就是"慢半拍"；现在按下即发、拖动只留最新一跳。
 */
internal object VirtualScreenInputPump {
    private class Item(
        val context: Context,
        val sessionId: String,
        val args: JSONObject,
        val onResult: ((Boolean) -> Unit)?,
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xaw-input").apply { isDaemon = true }
    }
    private val lock = Any()
    private val queue = java.util.ArrayDeque<Item>()
    private var running = false

    fun submit(context: Context, sessionId: String, args: JSONObject, onResult: ((Boolean) -> Unit)? = null) {
        VirtualScreenFrameGate.markInput()
        val item = Item(context.applicationContext, sessionId, args, onResult)
        synchronized(lock) {
            if (args.optString("action") == "swipe") {
                val last = queue.peekLast()
                if (last != null && last.args.optString("action") == "swipe") queue.pollLast()
            }
            queue.addLast(item)
            if (running) return
            running = true
        }
        executor.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val item = synchronized(lock) {
                val head = queue.pollFirst()
                if (head == null) running = false
                head
            } ?: return
            val ok = runCatching {
                val result = VirtualScreenSession.inputForViewer(item.context, item.sessionId, item.args)
                JSONObject(result.content).optBoolean("ok")
            }.getOrDefault(false)
            item.onResult?.invoke(ok)
        }
    }
}
