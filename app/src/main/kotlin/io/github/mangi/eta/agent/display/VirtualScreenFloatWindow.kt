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
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.R
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
    private var borderDrawable: GradientDrawable? = null
    private var surfaceView: VirtualScreenSurfaceView? = null
    private var triggerBar: View? = null
    private var actionBar: View? = null
    private val longPress = Runnable { showActionBar() }
    private var lp: WindowManager.LayoutParams? = null

    private var info: VirtualDisplayInfo? = null
    private var lastFrameId = 0L
    private var frameJob: Job? = null
    private var observeJob: Job? = null
    private var cachedSettings: Settings? = null

    private var collapsed = false
    private var collapsedSide = SIDE_LEFT
    /** 内容区短边（dp）。横竖屏切换时保持不变，只换比例，保证两种朝向的小窗“大小手感”一致。 */
    private var innerShortDp = 0f
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
    private val minWidthDp = 72

    /** 点一下小窗时切换到的另一档：最小档 + 1/3。 */
    private val TAP_GROW_SCALE = 4f / 3f
    private val maxWidthDp = 420

    /** surface 四周留出的边框宽度（dp，单边），窗口尺寸换算时必须扣掉，否则内容区比例 ≠ 内容比例会出现信箱黑边。 */
    private val BORDER_DP = 6f

    /** 长边最多占屏幕对应边长的比例；留出余量，保证横屏后单指拖拽仍有可移动空间。 */
    private val MAX_LONG_SCREEN_RATIO = 0.85f

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
            outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx(view.width, view.height))
        }
    }

    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (rootView == null) return
            // 小窗形状跟随虚拟屏内容方向；没有内容信息时才退回按手机横竖屏对调，避免横屏应用被转回竖长条。
            if (!alignToContent()) {
                val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
                if (isLandscape != (curWidthDp >= curHeightDp)) {
                    val tmp = curWidthDp; curWidthDp = curHeightDp; curHeightDp = tmp
                }
            }
            normalizeSize()
            if (collapsed) {
                // 折叠状态在旋转后仍必须是气泡：按展开尺寸改窗口会露出一个大白框。
                reCollapse()
            } else {
                applySize()
                clampToScreen()
            }
        }
        override fun onLowMemory() {}
    }

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (collapsed) return false
            val factor = detector.scaleFactor
            curWidthDp = (curWidthDp * factor).roundToInt().coerceAtLeast(1)
            curHeightDp = (curHeightDp * factor).roundToInt().coerceAtLeast(1)
            normalizeSize()
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
                        else -> {
                            val shapeChanged = info?.let { it.width != next.width || it.height != next.height } == true
                            info = next
                            rootView?.alpha = windowAlpha
                            if (shapeChanged) fitContentShape(next)
                        }
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
            curWidthDp = minWidthDp
            curHeightDp = (curWidthDp * (long / short)).roundToInt()
            windowAlpha = 0.85f
            savedFloatWinX = 16; savedFloatWinY = 120
            rootView?.alpha = windowAlpha
            lp?.x = 16; lp?.y = 120
            innerShortDp = 0f
            alignToContent()
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
        // 每次出现都从最小档开始：贴边展开、直接弹出都一致；之后的大小由双指决定。
        val defaultW = minWidthDp
        curWidthDp = defaultW; curHeightDp = (defaultW * (long / short)).roundToInt()
        innerShortDp = 0f
        alignToContent()
        normalizeSize()
        savedFloatWinX = settings.virtualScreenFloatWindowPosX
        savedFloatWinY = settings.virtualScreenFloatWindowPosY

        val border = (BORDER_DP * d).roundToInt()

        val root = FrameLayout(ctx)
        // 白透边自身也带圆角：即使个别 ROM 的 clipToOutline 生效延迟，角上也不会是直角。
        borderDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx(dp(curWidthDp.toFloat()), dp(curHeightDp.toFloat()))
            setColor(0xB3FFFFFF.toInt())
        }
        root.background = borderDrawable
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
        rootView = null; surfaceView = null; triggerBar = null; lp = null; actionBar = null
        info = null; lastFrameId = 0; collapsed = false
    }

    private fun startFrameLoop() {
        frameJob = scope.launch {
            val frameDelay = (io.github.mangi.eta.data.model.VirtualScreenFrameRate.intervalMs(
                io.github.mangi.eta.data.datastore.SettingsDataStore.settings().virtualScreenFrameRate) / 2).toLong().coerceIn(6L, 33L)
            val idleDelay = frameDelay.coerceAtLeast(120L)
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
                val touching = android.os.SystemClock.elapsedRealtime() -
                    (VirtualScreenSession.state.value.gesture?.startedAt ?: 0L) < 900L
                delay(if (touching) 4L else idleDelay)
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
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }) ?: return@withContext null
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
                // 操作条在就表示用户正要选动作：这一下只用来收起它，不重新计时。
                if (actionBar != null) hideActionBar() else rootView?.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downRawX).roundToInt()
                val dy = (ev.rawY - downRawY).roundToInt()
                if (abs(dx) > 4 || abs(dy) > 4) dragMoved = true
                if (dragMoved) cancelLongPress()
                if (scaleDetector.isInProgress) cancelLongPress()
                if (dragMoved && !scaleDetector.isInProgress) {
                    // 拖拽全程限制在屏幕内：只有真正贴到左右边缘松手，才会折叠成气泡。
                    p.x = (downWinX + dx).coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
                    p.y = (downWinY + dy).coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
                    runCatching { wm?.updateViewLayout(rootView, p) }
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                if (!dragMoved) {
                    if (detectDoubleTap(ev)) enterFullScreen() else tapToggleSize()
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
        borderDrawable?.let { it.cornerRadius = cornerRadiusPx(p.width, p.height) }
        rootView?.invalidateOutline()
        runCatching { wm?.updateViewLayout(rootView, p) }
    }

    /**
     * 让窗口形状跟随虚拟屏内容方向，并让"内容区"比例严格等于内容比例（消除信箱黑边）。
     * 拿到内容尺寸时总是按内容比例重算并返回 true；没有内容信息时返回 false，由调用方退回按手机横竖屏对调。
     */
    private fun alignToContent(): Boolean {
        val display = info ?: return false
        if (display.width <= 0 || display.height <= 0) return false
        val aspect = display.width.toFloat() / display.height
        if (!aspect.isFinite() || aspect <= 0f) return false
        // 保留“内容区短边”，只换比例：竖屏多大，转横屏还是多大，包边厚度与圆角比例在两种朝向一致，
        // 也不会一转到横屏就把小窗撑满整块屏幕。
        val innerShort = if (innerShortDp > 0f) {
            innerShortDp
        } else {
            (if (aspect >= 1f) curHeightDp else curWidthDp) - BORDER_DP * 2f
        }
        setFromInnerShort(innerShort, aspect)
        return true
    }

    /**
     * 按内容比例约束尺寸：内容区短边限制在 [minWidthDp, maxWidthDp]，长边不超过屏幕对应边长。
     * 竖屏内容等价于原来的"宽度 45–420dp"；横屏内容允许窗口铺到屏幕宽度，保证拖拽与双指缩放仍可用。
     * 有内容信息时走 setFromInnerShort（扣边框、严格等比）；没有内容信息时才退回按当前外框等比缩放。
     */
    private fun normalizeSize() {
        if (curWidthDp <= 0 || curHeightDp <= 0) return
        val display = info
        if (display != null && display.width > 0 && display.height > 0) {
            val aspect = display.width.toFloat() / display.height
            if (aspect.isFinite() && aspect > 0f) {
                val innerShort = (if (aspect >= 1f) curHeightDp else curWidthDp) - BORDER_DP * 2f
                setFromInnerShort(innerShort, aspect)
                return
            }
        }
        val d = context?.resources?.displayMetrics?.density ?: 1f
        val landscape = curWidthDp > curHeightDp
        val short = if (landscape) curHeightDp else curWidthDp
        val long = if (landscape) curWidthDp else curHeightDp
        val boundedShort = short.coerceIn(minWidthDp, maxWidthDp)
        val limit = (if (landscape) (screenW() / d).roundToInt() else (screenH() / d).roundToInt())
            .coerceAtLeast(boundedShort)
        val boundedLong = (long.toFloat() * boundedShort / short).roundToInt().coerceIn(boundedShort, limit)
        if (landscape) {
            curWidthDp = boundedLong; curHeightDp = boundedShort
        } else {
            curWidthDp = boundedShort; curHeightDp = boundedLong
        }
    }

    /**
     * 由"内容区短边 + 内容比例"换算出窗口外框尺寸（dp）。
     * 外框 = 内容区在两侧各加 BORDER_DP，与 show() 里 surface 的边距一致，
     * 这样 ImageView(FIT_CENTER) 的可用区域与内容等比，横竖屏都不会出现信箱黑边。
     */
    private fun setFromInnerShort(innerShortIn: Float, aspect: Float) {
        val landscape = aspect >= 1f
        val d = context?.resources?.displayMetrics?.density ?: 1f
        val b = (BORDER_DP * 2f).roundToInt()
        var innerShort = innerShortIn.coerceIn(minWidthDp.toFloat(), maxWidthDp.toFloat())
        var innerLong = innerShort * (if (landscape) aspect else 1f / aspect)
        val limitDp = (if (landscape) screenW() else screenH()) / d * MAX_LONG_SCREEN_RATIO
        if (innerLong + b > limitDp) {
            innerLong = (limitDp - b).coerceAtLeast(innerShort)
            innerShort = if (landscape) innerLong / aspect else innerLong * aspect
        }
        val wDp = (if (landscape) innerLong else innerShort).roundToInt() + b
        val hDp = (if (landscape) innerShort else innerLong).roundToInt() + b
        curWidthDp = wDp.coerceAtLeast(minWidthDp + b)
        curHeightDp = hDp.coerceAtLeast(minWidthDp + b)
        innerShortDp = innerShort
    }

    /** 内容横竖切换后重算窗口形状与位置；拖拽与双指缩放沿用同一套手势逻辑，不因形状变化失效。 */
    private fun fitContentShape(display: VirtualDisplayInfo) {
        if (collapsed || display.width <= 0 || display.height <= 0) return
        alignToContent()
        normalizeSize()
        applySize()
        clampToScreen()
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

    private fun cancelLongPress() {
        runCatching { rootView?.removeCallbacks(longPress) }
    }

    private fun hideActionBar() {
        actionBar?.let { bar -> runCatching { rootView?.removeView(bar) } }
        actionBar = null
    }

    /** 长按小窗弹两个动作：贴边折叠、关闭虚拟屏。 */
    private fun showActionBar() {
        val root = rootView ?: return
        val ctx = context ?: return
        if (collapsed || actionBar != null) return
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16f).toFloat()
                setColor(0xE62A2A2A.toInt())
            }
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            fun add(label: String, onClick: () -> Unit) {
                addView(actionButton(ctx, label, onClick), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(dp(2f), 0, dp(2f), 0) })
            }
            add(ctx.getString(R.string.virtual_screen_action_close)) {
                hideActionBar()
                val owner = VirtualScreenSession.state.value.display?.owner
                if (owner.isNullOrBlank()) VirtualScreenSession.revokePermission()
                else VirtualScreenSession.closeOwner(owner)
                hide()
            }
        }
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        actionBar = bar
        // 不自动消失：一直留着，直到点关闭或点小窗别处收起，避免来不及点。
    }

    private fun actionButton(ctx: Context, label: String, onClick: () -> Unit): TextView {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()
        return TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener { onClick() }
        }
    }

    /**
     * 只有窗口真正贴到屏幕左右边缘（横竖屏同规则）才折叠成气泡。
     * 原先 4dp 阈值会让“还差一点碰到”也折叠，横屏窗口更宽时尤其容易被误判。
     * 拖拽已经限制在屏幕内，所以贴边时 x 恰好是 0 或 屏宽-窗宽，这里留 1px 容差兜住取整误差。
     */
    private fun maybeCollapse() {
        val p = lp ?: return
        if (p.x <= 1) collapse(SIDE_LEFT)
        else if (p.x + expandedWidthPx() >= screenW() - 1) collapse(SIDE_RIGHT)
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
        resetToMinimumSize()
        p.width = expandedWidthPx()
        p.height = expandedHeightPx()
        p.x = preCollapseX.coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
        p.y = preCollapseY.coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(rootView, p) }
    }

    /** 内容比例（宽/高）；没有内容信息时返回 0。 */
    private fun contentAspect(): Float {
        val display = info ?: return 0f
        if (display.width <= 0 || display.height <= 0) return 0f
        val aspect = display.width.toFloat() / display.height
        return if (aspect.isFinite() && aspect > 0f) aspect else 0f
    }

    /** 回到最小档：贴边展开、重新出现都用它，横竖屏都按内容比例等比。 */
    private fun resetToMinimumSize() {
        val aspect = contentAspect()
        if (aspect > 0f) {
            setFromInnerShort(minWidthDp.toFloat(), aspect)
            return
        }
        val ratio = if (curWidthDp > 0) curHeightDp.toFloat() / curWidthDp else 1f
        curWidthDp = minWidthDp
        curHeightDp = (minWidthDp * ratio).roundToInt().coerceAtLeast(minWidthDp)
    }

    /**
     * 点一下小窗：在“最小档”和“最小档 +1/3”之间来回切。
     * 已经比这一档大（自己捏过）时，点一下回到最小档。
     */
    private fun tapToggleSize() {
        if (collapsed) return
        val aspect = contentAspect()
        val border = (BORDER_DP * 2f).roundToInt()
        val stepInner = minWidthDp * TAP_GROW_SCALE
        if (aspect > 0f) {
            val landscape = aspect >= 1f
            val innerShort = if (landscape) curHeightDp - border else curWidthDp - border
            setFromInnerShort(if (innerShort >= stepInner - 1f) minWidthDp.toFloat() else stepInner, aspect)
        } else {
            val current = minOf(curWidthDp, curHeightDp).coerceAtLeast(1)
            val target = if (current <= minWidthDp + border + 1) stepInner.roundToInt() + border else minWidthDp + border
            val scale = target.toFloat() / current
            curWidthDp = (curWidthDp * scale).roundToInt().coerceAtLeast(1)
            curHeightDp = (curHeightDp * scale).roundToInt().coerceAtLeast(1)
            normalizeSize()
        }
        applySize()
        clampToScreen()
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

    /** 折叠状态：窗口保持气泡大小并贴到当前方向的边缘，避免旋转后变成又大又淡的白框。 */
    private fun reCollapse() {
        val p = lp ?: return
        val d = context?.resources?.displayMetrics?.density ?: 1f
        val bubble = (35f * d).roundToInt()
        p.width = bubble
        p.height = bubble
        p.x = if (collapsedSide == SIDE_LEFT) 0 else (screenW() - bubble).coerceAtLeast(0)
        p.y = p.y.coerceIn(0, (screenH() - bubble).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(rootView, p) }
    }

    /** 圆角半径（px）：随窗口短边轻微缩放（16–24dp），横竖屏看到的是同一种角。 */
    private fun cornerRadiusPx(widthPx: Int, heightPx: Int): Float {
        val d = context?.resources?.displayMetrics?.density ?: 1f
        if (widthPx <= 0 || heightPx <= 0) return 22f * d
        val shortDp = min(widthPx, heightPx) / d
        return (shortDp * 0.09f).coerceIn(16f, 24f) * d
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
