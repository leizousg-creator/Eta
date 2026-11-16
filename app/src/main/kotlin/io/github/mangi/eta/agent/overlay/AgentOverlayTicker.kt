package io.github.mangi.eta.agent.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 顶部流式字幕条：模型正在吐出的回答，贴在屏幕最上方，最多三行。
 *
 * 和胶囊的分工：胶囊说「在干什么」（思考/操作工具），字幕条说「说了什么」。
 * 长任务时用户常常切到别的应用，胶囊只有一行状态，看不到回答长什么样；
 * 这条字幕让回答在屏幕顶部滚动，不用切回对话。
 *
 * 只在运行中且有实际文本时渲染；其余情况返回空 Composable，窗口高度自然塌成 0。
 */
@Composable
internal fun AgentOverlayTicker(state: AgentOverlayState) {
    if (state.phase != AgentOverlayPhase.RUNNING) return
    val text = state.detailText.trim()
    if (text.isEmpty()) return

    // 只保留最后三行非空内容；每行限长，避免一整段挤成一行横穿屏幕。
    val lines = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeLast(3)
    if (lines.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            lines.forEach { line ->
                Text(
                    text = line,
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
