package io.github.mangi.eta.ui.components

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.window.WindowListPopup
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 顶栏内存胶囊：显示物理内存已用百分比，点一下看已用 / 可用 / 总量。
 * 只读 ActivityManager.MemoryInfo，不需要 Root，也不改动任何系统设置。
 */
@Composable
internal fun MemoryUsageCapsule() {
    val context = LocalContext.current
    var usage by remember { mutableStateOf(readMemoryUsage(context)) }
    var showMenu by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        while (true) {
            usage = readMemoryUsage(context)
            delay(MEMORY_REFRESH_MILLIS)
        }
    }
    Box {
        StatusCapsule(
            text = stringResource(R.string.memory_capsule, usage.usedPercent),
            modifier = Modifier.clickable { showMenu = true },
            description = stringResource(R.string.memory_capsule_description, usage.usedPercent),
        )
        WindowListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            val items = listOf(
                DropdownItem(
                    text = stringResource(
                        R.string.memory_capsule_used,
                        formatGigabytes(usage.usedBytes),
                        formatGigabytes(usage.totalBytes),
                    ),
                ),
                DropdownItem(
                    text = stringResource(
                        R.string.memory_capsule_available,
                        formatGigabytes(usage.availableBytes),
                    ),
                ),
            )
            ListPopupColumn {
                items.forEachIndexed { index, item ->
                    DropdownImpl(
                        item = item,
                        optionSize = items.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = { showMenu = false },
                    )
                }
            }
        }
    }
}

private data class MemoryUsage(
    val usedPercent: Int,
    val usedBytes: Long,
    val availableBytes: Long,
    val totalBytes: Long,
)

private fun readMemoryUsage(context: Context): MemoryUsage {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val info = ActivityManager.MemoryInfo()
    runCatching { manager?.getMemoryInfo(info) }
    val total = info.totalMem.takeIf { it > 0 } ?: 1L
    val available = info.availMem.coerceAtLeast(0L)
    val used = (total - available).coerceAtLeast(0L)
    val percent = ((used.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    return MemoryUsage(percent, used, available, total)
}

private fun formatGigabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))

private const val MEMORY_REFRESH_MILLIS = 5000L
