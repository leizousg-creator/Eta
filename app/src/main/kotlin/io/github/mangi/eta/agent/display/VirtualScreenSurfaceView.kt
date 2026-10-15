package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.mangi.eta.agent.overlay.GestureIndicatorHost
import org.json.JSONObject
import kotlin.math.hypot

/**
 * 虚拟屏画面宿主。两种出帧方式共用同一个控件：
 * - 直通（direct）：TextureView 的 Surface 交给 root helper 当虚拟屏输出面，合成器直接画进来，零拷贝。
 * - 回落（fallback）：沿用截屏→JPEG→base64 的整帧管线，画到 ImageView 上。
 * TextureView 用普通 View 合成，不挖窗口空洞，手势指示器可以正常叠在上面。
 */
internal class VirtualScreenSurfaceView(context: Context) : FrameLayout(context) {
    private val video = TextureView(context).apply {
        isOpaque = true
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                texture = st
                // 直通时缓冲尺寸必须等于虚拟屏尺寸：合成端按虚拟屏尺寸入队、消费端按控件尺寸出队会错位甚至崩 native。
                info?.let { runCatching { st.setDefaultBufferSize(it.width, it.height) } }
                val next = Surface(st)
                hostSurface = next
                onHostSurface?.invoke(next)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                val previous = hostSurface
                hostSurface = null
                texture = null
                onHostSurface?.invoke(null)
                runCatching { previous?.release() }
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val indicators = GestureIndicatorHost(context)
    private var bitmap: Bitmap? = null
    private var info: VirtualDisplayInfo? = null
    private var lastGesture = 0L
    private var down: MotionEvent? = null
    private var downSession: String? = null
    private var hostSurface: Surface? = null
    private var texture: SurfaceTexture? = null

    /** direct=true 时画面由合成器直出，不再吃整帧。 */
    var direct = false
        private set

    /** true=等比完整显示（旧小窗 FIT_CENTER 行为）；false=等比铺满裁切（查看页全屏行为）。 */
    var fitContent = false

    var onInput: (String, JSONObject) -> Unit = { _, _ -> }
    var onHostSurface: (Surface?) -> Unit = {}
    var touchEnabled = true

    /** 真实帧尺寸：窗口比例与触控换算都以它为准，保证投影铺满、不出现信箱黑边。 */
    private var frameWidth = 0
    private var frameHeight = 0
    private var lastViewportLog = ""
    var onFrameSize: ((Int, Int) -> Unit)? = null

    init {
        video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val d = info ?: return@addOnLayoutChangeListener
            texture?.let { runCatching { it.setDefaultBufferSize(d.width, d.height) } }
        }
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(indicators, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** 切换直通/回落。回落时把 TextureView 收起，释放取面，画面交回整帧管线。 */
    fun setDirectMode(enabled: Boolean) {
        if (direct == enabled) return
        direct = enabled
        if (enabled) {
            image.setImageDrawable(null)
            bitmap?.let { runCatching { it.recycle() } }
            bitmap = null
        } else {
        }
        // 直通切换不触发布局：必须自己重算一次变换，否则会留着上一次的缩放矩阵，
        // 合成器写进 buffer 的整屏画面会被再缩一次，小窗里就只剩中间一小块。
        applyViewport()
        VscrDiag.line("直通切换 direct=" + direct + " 视图=" + width + "x" + height +
            " 帧=" + frameWidth + "x" + frameHeight)
    }

    fun setFrame(next: Bitmap, display: VirtualDisplayInfo) {
        info = display
        if (next.width > 0 && next.height > 0 && (next.width != frameWidth || next.height != frameHeight)) {
            frameWidth = next.width
            frameHeight = next.height
            onFrameSize?.invoke(next.width, next.height)
        }
        if (direct) {
            next.recycle()
            return
        }
        image.setImageBitmap(next)
        bitmap?.takeIf { it !== next }?.recycle()
        bitmap = next
    }

    /** 当前已推给控件的源尺寸，供外层判断要不要再推一次。 */
    val infoWidth: Int get() = info?.width ?: 0
    val infoHeight: Int get() = info?.height ?: 0

    fun setDisplayInfo(display: VirtualDisplayInfo?) {
        info = display
        if (display != null) texture?.let { runCatching { it.setDefaultBufferSize(display.width, display.height) } }
        applyViewport()
    }

    // fill the whole box, keep the source aspect ratio (center-crop like a full screen)
    private fun applyViewport() {
        val display = info ?: return
        val vw = width
        val vh = height
        val w = if (frameWidth > 0) frameWidth else display.width
        val h = if (frameHeight > 0) frameHeight else display.height
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        // TextureView resets the buffer size to its own view size on layout; force it back to the
        // virtual display size or SurfaceFlinger only fills a corner of the buffer.
        texture?.let { runCatching { it.setDefaultBufferSize(display.width, display.height) } }
        // 直通时也一律按“装得下”算：换横竖屏的中间几帧窗口还没跟上，
        // 用“铺满”会把画面放大到视图外，看起来就只剩一半、其余发白。
        val scale = if (fitContent || direct) {
            minOf(vw.toFloat() / w, vh.toFloat() / h)
        } else {
            maxOf(vw.toFloat() / w, vh.toFloat() / h)
        }
        val matrix = android.graphics.Matrix()
        // 直通：合成器把整屏写进 buffer，TextureView 默认已铺满控件，任何缩放都会让画面缩成中间一小块。
        // 回落：画面由 ImageView(FIT_CENTER) 整帧显示，TextureView 必须收起且不得残留缩放矩阵。
        matrix.reset()
        video.setTransform(matrix)
        video.visibility = if (direct) View.VISIBLE else View.GONE
        image.visibility = if (direct) View.GONE else View.VISIBLE
        val tag = "视图=" + vw + "x" + vh + " 源=" + w + "x" + h + " direct=" + direct +
            " fitContent=" + fitContent + " scale=" + String.format("%.4f", scale) +
            " 实画=" + (w * scale).toInt() + "x" + (h * scale).toInt()
        if (tag != lastViewportLog) {
            lastViewportLog = tag
            VscrDiag.line("画面 " + tag)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyViewport()
    }

    fun clearFrame() {
        info = null
        frameWidth = 0
        frameHeight = 0
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        cancelTouch()
    }

    fun showGesture(gesture: VirtualScreenGesture?) {
        val display = info ?: return
        if (gesture == null || gesture.id == lastGesture || gesture.sessionId != display.sessionId) return
        lastGesture = gesture.id
        if (SystemClock.elapsedRealtime() - gesture.startedAt > gesture.durationMs + 700) return
        val viewport = viewport() ?: return
        val x = viewport.left + gesture.x * viewport.scale
        val y = viewport.top + gesture.y * viewport.scale
        if (gesture.action == "swipe") {
            indicators.showSwipe(
                x, y, viewport.left + gesture.endX * viewport.scale,
                viewport.top + gesture.endY * viewport.scale, gesture.durationMs
            )
        } else indicators.showPress(x, y, gesture.action == "long_press", gesture.durationMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchEnabled || info == null || (!direct && bitmap == null)) {
            cancelTouch(); return false
        }
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelTouch(); return true
        }
        val viewport = viewport() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                if (viewport.toDisplay(event.x, event.y) == null) return false
                down = MotionEvent.obtain(event)
                downSession = info?.sessionId
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_UP -> {
                val first = down ?: return true
                val session = downSession
                val start = viewport.toDisplay(first.x, first.y)
                val end = viewport.toDisplay(event.x, event.y, clamp = true)
                if (session != null && session == info?.sessionId && start != null && end != null) {
                    val duration = (event.eventTime - first.eventTime).toInt().coerceIn(100, 3000)
                    val moved = hypot(
                        event.x - first.x,
                        event.y - first.y
                    ) > ViewConfiguration.get(context).scaledTouchSlop
                    val args = if (moved) JSONObject().put("action", "swipe")
                        .put("x1", start.x).put("y1", start.y).put("x2", end.x).put("y2", end.y)
                        .put("durationMs", duration.coerceAtMost(2000))
                    else JSONObject().put(
                        "action",
                        if (duration >= ViewConfiguration.getLongPressTimeout()) "long_press" else "tap"
                    )
                        .put("x", start.x).put("y", start.y).put("durationMs", duration)
                    onInput(session, args)
                    performClick()
                }
                cancelTouch()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick(); return true
    }

    // 与 applyViewport 同一套比例：小窗等比完整显示，查看页等比裁切。
    private fun viewport(): VirtualScreenViewport? = info?.let {
        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) return null
        val w = if (frameWidth > 0) frameWidth else it.width
        val h = if (frameHeight > 0) frameHeight else it.height
        if (w <= 0 || h <= 0) return null
        val scale = if (fitContent || direct) minOf(vw.toFloat() / w, vh.toFloat() / h)
        else maxOf(vw.toFloat() / w, vh.toFloat() / h)
        VirtualScreenViewport((vw - w * scale) / 2f, (vh - h * scale) / 2f, scale, w, h)
    }

    /** 控件坐标 -> 虚拟屏像素（触控直通用）。 */
    fun toDisplay(x: Float, y: Float, clamp: Boolean = false): VirtualScreenViewport.Point? =
        viewport()?.toDisplay(x, y, clamp)

    private fun cancelTouch() {
        down?.recycle()
        down = null
        downSession = null
    }

    override fun onDetachedFromWindow() {
        clearFrame()
        super.onDetachedFromWindow()
    }
}
