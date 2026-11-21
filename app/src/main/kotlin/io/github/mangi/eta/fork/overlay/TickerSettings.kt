package io.github.mangi.eta.fork.overlay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 顶部流式字幕条的显示配置：开关、行数、底色透明度。
 *
 * 用 SharedPreferences + StateFlow：设置页改动立即反映到浮窗（浮窗的 ComposeView 订阅 flow），
 * 不需要重启服务。默认 3 行 / 72% 黑底，和第一版一致。
 */
internal object TickerSettings {
    private const val PREFS = "eta_ticker"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LINES = "lines"
    private const val KEY_ALPHA = "alpha"

    val lineOptions = listOf(2, 3, 5)
    val alphaOptions = listOf(0.30f, 0.50f, 0.72f, 0.85f)

    data class Config(
        /**
         * 默认关闭。
         *
         * 它是纯附加浮窗：状态栏已有原子岛胶囊、通知栏有前台通知，
         * 再叠一条黑底字幕条会让顶部同时出现三个 Eta 元素，用户第一反应是"怎么这么多岛"。
         * 需要边跑边看流式输出的，在设置里手动打开。
         */
        val enabled: Boolean = false,
        val lines: Int = 3,
        val alpha: Float = 0.72f,
    )

    private val state = MutableStateFlow(Config())
    val flow: StateFlow<Config> get() = state

    fun current(): Config = state.value

    fun load(context: Context) {
        val prefs = prefs(context)
        state.value = Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            lines = prefs.getInt(KEY_LINES, 3).takeIf { it in lineOptions } ?: 3,
            alpha = prefs.getFloat(KEY_ALPHA, 0.72f)
                .takeIf { value -> alphaOptions.any { kotlin.math.abs(it - value) < 0.01f } } ?: 0.72f,
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) = persist(context) { it.copy(enabled = enabled) }

    fun cycleLines(context: Context) = persist(context) {
        val next = lineOptions[(lineOptions.indexOf(it.lines).coerceAtLeast(0) + 1) % lineOptions.size]
        it.copy(lines = next)
    }

    fun cycleAlpha(context: Context) = persist(context) {
        val index = alphaOptions.indexOfFirst { option -> kotlin.math.abs(option - it.alpha) < 0.01f }
        val next = alphaOptions[((if (index < 0) 2 else index) + 1) % alphaOptions.size]
        it.copy(alpha = next)
    }

    fun linesLabel(lines: Int): String = "$lines 行"

    fun alphaLabel(alpha: Float): String = "${(alpha * 100).toInt()}%"

    private fun persist(context: Context, transform: (Config) -> Config) {
        val next = transform(state.value)
        state.value = next
        runCatching {
            prefs(context).edit()
                .putBoolean(KEY_ENABLED, next.enabled)
                .putInt(KEY_LINES, next.lines)
                .putFloat(KEY_ALPHA, next.alpha)
                .apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
