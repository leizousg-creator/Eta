package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分段复制面板：内容按空行切段，每段可整段一键复制，也能在段内拖选任意片段。
 * 这里的文本是静态的，不随消息流式重排，也不跨横向滚动容器，所以选择稳定。
 */
@Composable
internal fun SelectionCopyDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copiedKey by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(copiedKey) {
        if (copiedKey != null) {
            delay(1_400)
            copiedKey = null
        }
    }
    val segments = remember(text) { splitCopySegments(text) }
    fun copy(key: Int, value: String) {
        @Suppress("DEPRECATION")
        clipboard.setText(AnnotatedString(value))
        copiedKey = key
    }

    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.selection_copy_title),
        summary = stringResource(R.string.selection_copy_hint),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EtaTextButton(
                text = stringResource(
                    if (copiedKey == 0) R.string.copy_copied else R.string.selection_copy_all,
                ),
                onClick = { copy(0, text) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (segments.size > 1) {
                Text(
                    text = stringResource(R.string.selection_copy_segments_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            segments.forEachIndexed { index, segment ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.width(24.dp),
                    )
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = segment,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = { copy(index + 1, segment) },
                        minWidth = 30.dp,
                        minHeight = 30.dp,
                    ) {
                        Icon(
                            imageVector = if (copiedKey == index + 1) {
                                Icons.Rounded.Check
                            } else {
                                Icons.Rounded.ContentCopy
                            },
                            contentDescription = stringResource(R.string.ui_copy_4edd1d),
                            modifier = Modifier.size(14.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            EtaTextButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 按空行切段：段内保持原有换行，便于整段复制或段内拖选。 */
private fun splitCopySegments(text: String): List<String> {
    val segments = ArrayList<String>()
    val buffer = StringBuilder()
    fun flush() {
        if (buffer.isNotBlank()) segments.add(buffer.toString().trim())
        buffer.setLength(0)
    }
    for (line in text.lines()) {
        if (line.isBlank()) {
            flush()
        } else {
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(line)
        }
    }
    flush()
    return segments.ifEmpty { listOf(text.trim()) }
}
