package io.github.mangi.eta.agent.display

import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 虚拟屏幕悬浮小窗【最终版·无标题栏】
 * 纯监控：画面区单指拖拽移动、双指缩放、双击跳全屏；无顶部黑条；四角圆角；贴边折叠；
 * 透明度、尺寸/位置持久化、一键重置、帧异常捕获。
 */
internal object VirtualScreenFloatWindow {
    private var context: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val wm: WindowManager?
        get() = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var rootView: FrameLayout? = null
    private var surfaceView: VirtualScreenSurfaceView? = null
    private var triggerBar: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private var info: VirtualDisplayInfo? = null
    private var lastFrameId = 0L
    private var frameJob: Job? = null
    private var observeJob: Job? = null
    private var cachedSettings: Settings? = null

    private var collapsed = false
    private var collapsedSide = SIDE_LEFT
    private var edgeCollapseEnabled = true
    private var windowAlpha = 0.85f
    private var preCollapseX = 0
    private var preCollapseY = 0

    private var isFullScreenActive = false
    private var savedFloatWinX = 16
    private var savedFloatWinY = 120

    private lateinit var scaleDetector: ScaleGestureDetector
    private var curWidthDp = 0
    private var curHeightDp = 0
    private val minWidthDp = 45
    private val maxWidthDp = 420

    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var dragMoved = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val doubleTapMaxIntervalMs = 300L
    private val doubleTapMaxDeltaDp = 20f

    private val floatWinOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = (22f * view.resources.displayMetrics.density).roundToInt().toFloat()
            outline.setRoundRect(0, 0, view.width, view.height, r)
        }
    }

    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (rootView == null) return
            // 横竖屏旋转：对调宽高比例，让小窗"跟着转"，不保持窄长条
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape != (curWidthDp >= curHeightDp)) {
                val tmp = curWidthDp; curWidthDp = curHeightDp; curHeightDp = tmp
            }
            applySize()
            if (collapsed) reCollapse() else clampToScreen()
        }
        override fun onLowMemory() {}
    }

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (collapsed) return false
            val newW = (curWidthDp * detector.scaleFactor).coerceIn(minWidthDp.toFloat(), maxWidthDp.toFloat())
            val ratio = curHeightDp.toFloat() / curWidthDp.toFloat()
            curWidthDp = newW.roundToInt()
            curHeightDp = (newW * ratio).roundToInt()
            applySize()
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (collapsed) return
            scope.launch {
                runCatching {
                    SettingsDataStore.updateSettings {
                        it.copy(virtualScreenFloatWindowWidthDp = curWidthDp,
                            virtualScreenFloatWindowHeightDp = curHeightDp)
                    }
                }
            }
        }
    }

    fun init(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        context?.registerComponentCallbacks(configCallback)
        observeJob = scope.launch {
            combine(SettingsDataStore.settingsFlow(), VirtualScreenSession.state) { s, v -> s to v }
                .collect { (settings, vsState) ->
                    cachedSettings = settings
                    edgeCollapseEnabled = settings.virtualScreenFloatEdgeCollapseEnabled
                    windowAlpha = settings.virtualScreenFloatWindowAlpha.coerceIn(0.1f, 1.0f)
                    val next = vsState.display
                    when {
                        !settings.virtualScreenFloatWindowEnabled || next == null -> hide()
                        isFullScreenActive -> Unit
                        next.sessionId != info?.sessionId -> show(next, settings)
                        else -> rootView?.alpha = windowAlpha
                    }
                }
        }
    }

    private fun enterFullScreen() {
        val p = lp ?: return
        savedFloatWinX = p.x
        savedFloatWinY = p.y
        isFullScreenActive = true
        hide()
        runCatching {
            context?.startActivity(
                Intent(context, VirtualScreenViewerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun exitFullScreen() {
        if (!isFullScreenActive) return
        isFullScreenActive = false
        val display = VirtualScreenSession.state.value.display
        val settings = cachedSettings ?: return
        if (display != null) {
            show(display, settings)
            lp?.let { p ->
                p.x = savedFloatWinX
                p.y = savedFloatWinY
                runCatching { wm?.updateViewLayout(rootView, p) }
            }
            clampToScreen()
        }
    }

    fun setWindowAlpha(alpha: Float) {
        windowAlpha = alpha.coerceIn(0.1f, 1.0f)
        rootView?.alpha = windowAlpha
    }

    fun resetWindowConfig() {
        scope.launch {
            runCatching {
                SettingsDataStore.updateSettings {
                    it.copy(
                        virtualScreenFloatWindowWidthDp = 0,
                        virtualScreenFloatWindowHeightDp = 0,
                        virtualScreenFloatWindowAlpha = 0.85f,
                        virtualScreenFloatWindowPosX = 16,
                        virtualScreenFloatWindowPosY = 120,
                    )
                }
            }
            val ctx = context ?: return@launch
            val d = ctx.resources.displayMetrics.density
            val short = min(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) / d
            val long = max(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) / d
            curWidthDp = (short * 0.2f).roundToInt().coerceIn(minWidthDp, maxWidthDp)
            curHeightDp = (curWidthDp * (long / short)).roundToInt()
            windowAlpha = 0.85f
            savedFloatWinX = 16; savedFloatWinY = 120
            rootView?.alpha = windowAlpha
            lp?.x = 16; lp?.y = 120
            applySize()
        }
    }

    private fun show(next: VirtualDisplayInfo, settings: Settings) {
        if (!canOverlay()) { requestOverlayPermission(); return }
        hide()
        info = next
        lastFrameId = 0
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()

        scaleDetector = ScaleGestureDetector(ctx, scaleListener)

        val short = min(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) / d
        val long = max(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) / d
        val defaultW = (short * 0.2f).roundToInt().coerceIn(minWidthDp, maxWidthDp)
        val storedW = settings.virtualScreenFloatWindowWidthDp.coerceIn(minWidthDp, maxWidthDp)
        val storedH = settings.virtualScreenFloatWindowHeightDp
        if (storedW >= minWidthDp && storedH > 0) {
            curWidthDp = storedW; curHeightDp = storedH
        } else {
            curWidthDp = defaultW; curHeightDp = (defaultW * (long / short)).roundToInt()
        }
        savedFloatWinX = settings.virtualScreenFloatWindowPosX
        savedFloatWinY = settings.virtualScreenFloatWindowPosY

        val border = dp(6f)

        val root = FrameLayout(ctx)
        root.setBackgroundColor(0xB3FFFFFF.toInt())
        root.alpha = windowAlpha
        root.outlineProvider = floatWinOutline
        root.clipToOutline = true

        val surface = VirtualScreenSurfaceView(ctx)
        surface.touchEnabled = false
        surface.setOnTouchListener { _, ev -> onSurfaceTouch(ev) }
        root.addView(surface, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { leftMargin = border; rightMargin = border; topMargin = border; bottomMargin = border })

        val trigger = View(ctx).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC3D3D3D.toInt())
                setStroke(dp(1f), 0x55FFFFFF.toInt())
            }
        }
        trigger.setOnTouchListener { _, ev -> onTriggerTouch(ev); true }
        root.addView(trigger, FrameLayout.LayoutParams(dp(35f), dp(35f), Gravity.START or Gravity.CENTER_VERTICAL))

        val p = WindowManager.LayoutParams(
            dp(curWidthDp.toFloat()), dp(curHeightDp.toFloat()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = savedFloatWinX; y = savedFloatWinY }

        runCatching { wm?.addView(root, p) }.onFailure { info = null; return }
        rootView = root; surfaceView = surface; triggerBar = trigger; lp = p
        startFrameLoop()
    }

    private fun hide() {
        frameJob?.cancel(); frameJob = null
        surfaceView?.clearFrame()
        rootView?.let { runCatching { wm?.removeView(it) } }
        rootView = null; surfaceView = null; triggerBar = null; lp = null
        info = null; lastFrameId = 0; collapsed = false
    }

    private fun startFrameLoop() {
        frameJob = scope.launch {
            while (isActive) {
                val current = info ?: break
                if (!collapsed) {
                    val frame = readFrame(current)
                    if (frame != null) {
                        if (info?.sessionId == current.sessionId) {
                            surfaceView?.setFrame(frame.bitmap, current)
                            lastFrameId = frame.id
                        } else frame.bitmap.recycle()
                    }
                }
                delay(33)
            }
        }
    }

    private suspend fun readFrame(current: VirtualDisplayInfo): Frame? = withContext(Dispatchers.IO) {
        return@withContext try {
            val result = VirtualScreenSession.observeForViewer(context ?: return@withContext null, lastFrameId)
            val json = JSONObject(result.content)
            if (!json.optBoolean("ok")) return@withContext null
            val encoded = result.images.firstOrNull()?.reference?.substringAfter("base64,")
                ?: return@withContext null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            Frame(bitmap, json.optLong("frameId"))
        } catch (_: Exception) { null }
    }

    private data class Frame(val bitmap: Bitmap, val id: Long)

    private fun onSurfaceTouch(ev: MotionEvent): Boolean {
        if (collapsed) return false
        scaleDetector.onTouchEvent(ev)
        val p = lp ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                downWinX = p.x; downWinY = p.y
                dragMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downRawX).roundToInt()
                val dy = (ev.rawY - downRawY).roundToInt()
                if (abs(dx) > 4 || abs(dy) > 4) dragMoved = true
                if (dragMoved && !scaleDetector.isInProgress) {
                    p.x = downWinX + dx; p.y = downWinY + dy
                    runCatching { wm?.updateViewLayout(rootView, p) }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragMoved) {
                    if (detectDoubleTap(ev)) enterFullScreen()
                } else {
                    savedFloatWinX = p.x; savedFloatWinY = p.y
                    if (edgeCollapseEnabled) maybeCollapse()
                    scope.launch {
                        runCatching {
                            SettingsDataStore.updateSettings {
                                it.copy(virtualScreenFloatWindowPosX = p.x, virtualScreenFloatWindowPosY = p.y)
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun detectDoubleTap(ev: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        val d = context?.resources?.displayMetrics?.density ?: 1f
        val dx = ev.x - lastTapX
        val dy = ev.y - lastTapY
        val distDp = sqrt(dx * dx + dy * dy) / d
        val isDouble = (now - lastTapTime) < doubleTapMaxIntervalMs && distDp < doubleTapMaxDeltaDp
        lastTapTime = now; lastTapX = ev.x; lastTapY = ev.y
        if (isDouble) lastTapTime = 0L
        return isDouble
    }

    private fun applySize() {
        val p = lp ?: return
        val d = context?.resources?.displayMetrics?.density ?: 1f
        p.width = (curWidthDp * d).roundToInt()
        p.height = (curHeightDp * d).roundToInt()
        runCatching { wm?.updateViewLayout(rootView, p) }
    }

    private fun onTriggerTouch(ev: MotionEvent) {
        val p = lp ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                downWinX = p.x; downWinY = p.y; dragMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downRawX).roundToInt()
                val dy = (ev.rawY - downRawY).roundToInt()
                if (abs(dx) > 8 || abs(dy) > 8) dragMoved = true
                if (dragMoved) {
                    expand()
                    p.x = (downWinX + dx).coerceIn(0, screenW() - expandedWidthPx())
                    p.y = (downWinY + dy).coerceIn(0, screenH() - expandedHeightPx())
                    runCatching { wm?.updateViewLayout(rootView, p) }
                }
            }
            MotionEvent.ACTION_UP -> if (!dragMoved) expand()
        }
    }

    private fun maybeCollapse() {
        val p = lp ?: return
        val thr = (4f * (context?.resources?.displayMetrics?.density ?: 1f)).roundToInt()
        if (p.x < thr) collapse(SIDE_LEFT)
        else if (p.x + expandedWidthPx() > screenW() - thr) collapse(SIDE_RIGHT)
    }

    private fun collapse(side: Int) {
        val p = lp ?: return
        val d = context?.resources?.displayMetrics?.density ?: 1f
        collapsed = true; collapsedSide = side
        preCollapseX = p.x; preCollapseY = p.y
        surfaceView?.visibility = View.GONE
        triggerBar?.visibility = View.VISIBLE
        (triggerBar?.layoutParams as? FrameLayout.LayoutParams)?.gravity =
            if (side == SIDE_LEFT) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL
        val bubble = (35f * d).roundToInt()
        p.y = (p.y + (p.height - bubble) / 2).coerceIn(0, (screenH() - bubble).coerceAtLeast(0))
        p.width = bubble
        p.height = bubble
        p.x = if (side == SIDE_LEFT) 0 else screenW() - bubble
        runCatching { wm?.updateViewLayout(rootView, p) }
    }

    private fun expand() {
        val p = lp ?: return
        collapsed = false
        surfaceView?.visibility = View.VISIBLE
        triggerBar?.visibility = View.GONE
        p.width = expandedWidthPx()
        p.height = expandedHeightPx()
        p.x = preCollapseX.coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
        p.y = preCollapseY.coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(rootView, p) }
    }

    private fun clampToScreen() {
        val p = lp ?: return
        val maxX = (screenW() - p.width).coerceAtLeast(0)
        val maxY = (screenH() - p.height).coerceAtLeast(0)
        val nx = p.x.coerceIn(0, maxX); val ny = p.y.coerceIn(0, maxY)
        if (nx != p.x || ny != p.y) {
            p.x = nx; p.y = ny
            runCatching { wm?.updateViewLayout(rootView, p) }
        }
    }

    private fun reCollapse() {
        val p = lp ?: return
        val nx = if (collapsedSide == SIDE_LEFT) 0 else (screenW() - p.width).coerceAtLeast(0)
        if (p.x != nx) { p.x = nx; runCatching { wm?.updateViewLayout(rootView, p) } }
    }

    private fun expandedWidthPx() = (curWidthDp * (context?.resources?.displayMetrics?.density ?: 1f)).roundToInt()
    private fun expandedHeightPx() = (curHeightDp * (context?.resources?.displayMetrics?.density ?: 1f)).roundToInt()
    private fun screenW() = context?.resources?.displayMetrics?.widthPixels ?: 0
    private fun screenH() = context?.resources?.displayMetrics?.heightPixels ?: 0

    private fun canOverlay() = context?.let { android.provider.Settings.canDrawOverlays(it) } == true
    private fun requestOverlayPermission() {
        val ctx = context ?: return
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private const val SIDE_LEFT = 0
    private const val SIDE_RIGHT = 1
}
