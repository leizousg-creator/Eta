package io.github.mangi.eta.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import java.io.File

/**
 * 全屏看图，手感对齐微信：
 * - 单击图片、左上角「✕」、返回键均可退出
 * - 双击在 1x / 2x 之间切换；双指缩放 1x~5x，放大后可单指拖动
 * - 一条消息多张图时左右翻页，顶部显示 n/m
 * - 长按弹出「保存到相册 / 分享」
 */
@Composable
internal fun ImagePreviewOverlay(
    images: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, images.lastIndex)) { images.size }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var menuVisible by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1800)
            hint = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f))) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val bitmap = rememberDataUrlBitmap(images[page])
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(page) {
                                detectTapGestures(
                                    onTap = { if (menuVisible) menuVisible = false else onDismiss() },
                                    onDoubleTap = {
                                        if (scale > 1f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2f
                                        }
                                    },
                                    onLongPress = { menuVisible = true },
                                )
                            }
                            .pointerInput(page) {
                                // 只有双指、或已经放大过，才接管手势；否则把水平拖动让给翻页。
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var pressed: Boolean
                                    do {
                                        val event = awaitPointerEvent()
                                        val pointerCount = event.changes.count { it.pressed }
                                        if (pointerCount > 1 || scale > 1f) {
                                            val zoom = event.calculateZoom()
                                            val pan = event.calculatePan()
                                            val next = (scale * zoom).coerceIn(1f, 5f)
                                            scale = next
                                            offset = if (next > 1f) offset + pan else Offset.Zero
                                            event.changes.forEach { it.consume() }
                                        }
                                        pressed = event.changes.any { it.pressed }
                                    } while (pressed)
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "✕",
                    color = Color.White,
                    fontSize = 20.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                )
                Spacer(Modifier.weight(1f))
                if (images.size > 1) {
                    Text("${pagerState.currentPage + 1}/${images.size}", color = Color.White, fontSize = 14.sp)
                }
            }

            hint?.let { text ->
                Text(
                    text,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            if (menuVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { menuVisible = false },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp)
                        .width(200.dp)
                        .background(Color(0xF2202020), RoundedCornerShape(14.dp))
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PreviewMenuRow("保存到相册") {
                        menuVisible = false
                        hint = if (saveDataUrlToGallery(context, images[pagerState.currentPage])) {
                            "已保存到相册 Pictures/Eta"
                        } else {
                            "保存失败"
                        }
                    }
                    PreviewMenuRow("分享") {
                        menuVisible = false
                        shareDataUrlImage(context, images[pagerState.currentPage])
                    }
                    Spacer(Modifier.height(2.dp))
                    PreviewMenuRow("取消") { menuVisible = false }
                }
            }
        }
    }
}

@Composable
private fun PreviewMenuRow(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

private fun dataUrlBytes(dataUrl: String): ByteArray? = runCatching {
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) null else Base64.decode(base64, Base64.NO_WRAP)
}.getOrNull()

private fun dataUrlExtension(dataUrl: String): String = when {
    dataUrl.startsWith("data:image/png", ignoreCase = true) -> "png"
    dataUrl.startsWith("data:image/webp", ignoreCase = true) -> "webp"
    else -> "jpg"
}

private fun dataUrlMime(dataUrl: String): String = "image/" + dataUrlExtension(dataUrl)

/** 写入系统相册（Android 10+ 无需额外权限）。 */
internal fun saveDataUrlToGallery(context: Context, dataUrl: String): Boolean {
    val bytes = dataUrlBytes(dataUrl) ?: return false
    val name = "eta_${System.currentTimeMillis()}." + dataUrlExtension(dataUrl)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, dataUrlMime(dataUrl))
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Eta")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
    }.isSuccess
    if (ok) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    return ok
}

/** 把图片落到缓存目录后经 FileProvider 分享出去。 */
internal fun shareDataUrlImage(context: Context, dataUrl: String) {
    val bytes = dataUrlBytes(dataUrl) ?: return
    val dir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File(dir, "eta_share_${System.currentTimeMillis()}." + dataUrlExtension(dataUrl))
    runCatching { file.writeBytes(bytes) }.getOrNull() ?: return
    val uri = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull() ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = dataUrlMime(dataUrl)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "分享图片"))
}
