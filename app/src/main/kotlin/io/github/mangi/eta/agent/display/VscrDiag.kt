package io.github.mangi.eta.agent.display

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 虚拟屏现场记录：只写状态与错误原因，不写画面、不解码帧数据。
 * 独立形态没有主进程 traces 目录，出问题时只能靠这份文件定位。
 */
internal object VscrDiag {
    private val lock = Any()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val tail = ArrayDeque<String>()

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        file = runCatching {
            val f = File(context.getExternalFilesDir(null), "vscr.log")
            f.parentFile?.mkdirs()
            f
        }.getOrNull()
    }

    fun line(message: String) {
        synchronized(lock) {
            val row = format.format(Date()) + " " + message
            tail.addLast(row)
            while (tail.size > 80) tail.removeFirst()
            val target = file ?: return
            runCatching { target.appendText(row + "\n") }
        }
    }

    fun tailText(maxChars: Int = 400): String = synchronized(lock) {
        tail.joinToString("\n").takeLast(maxChars)
    }
}
