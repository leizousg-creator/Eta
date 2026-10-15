package io.github.mangi.eta.agent.display

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.EtaPreferenceTheme
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class VirtualScreenViewerActivity : ComponentActivity() {
    private var surface: VirtualScreenSurfaceView? = null
    private var hasFrame by mutableStateOf(false)
    /** 直通出帧已接管画面：此时不再等整帧，也不显示"正在连接"。 */
    private var directReady by mutableStateOf(false)
    private var inputError by mutableStateOf(false)
    private var interactionEnabled by mutableStateOf(true)
    private var showHistory by mutableStateOf(false)
    private var lastFrameId = 0L
    private var textInputVisible by mutableStateOf(false)
    private var textDraft by mutableStateOf("")
    private var textPending by mutableStateOf(false)
    private var sessionError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initial = AppearanceSettings())
            AgentAppTheme(appearance, applyInterfaceScale = true) { ViewerScreen() }
        }
        // 独立形态没有会话来开屏：本页自己建屏，并让 helper 把系统桌面拉起来。
        lifecycleScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (attempt < 5 && !isFinishing) {
                if (VirtualScreenSession.state.value.display != null || VirtualScreenSession.isActive()) break
                attempt++
                runCatching {
                    VirtualScreenSession.execute(
                        context = applicationContext,
                        owner = "viewer",
                        args = JSONObject().put("action", "create"),
                    )
                }.onFailure { error ->
                    sessionError = error.message ?: "DISPLAY_UNAVAILABLE"
                    VscrDiag.line("viewer 建屏失败: " + sessionError)
                }
                if (VirtualScreenSession.state.value.display != null) { sessionError = null; break }
                delay(2000L)
            }
            if (VirtualScreenSession.state.value.display == null) {
                VscrDiag.line("建屏重试用尽，仍未拿到 display")
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    VirtualScreenSession.state.collect { state ->
                        if (state.display == null) { surface?.clearFrame(); hasFrame = false; lastFrameId = 0 }
                        val live = state.display != null && VirtualScreenHostSurfaces.live &&
                            VirtualScreenHostSurfaces.currentHost() != null
                        if (live != directReady) {
                            directReady = live
                            surface?.setDirectMode(live)
                            if (live) hasFrame = true
                        }
                        surface?.showGesture(state.gesture)
                    }
                }
                // 以前除以 2，高帧率档在查看页只有 30fps；现在按档位直出。
                val frameDelay = io.github.mangi.eta.data.model.VirtualScreenFrameRate.intervalMs(
                    io.github.mangi.eta.data.datastore.SettingsDataStore.settings().virtualScreenFrameRate).toLong()
                    .coerceIn(4L, 66L)
                val idleDelay = frameDelay.coerceAtLeast(40L)
                while (true) {
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    var fresh = false
                    val info = VirtualScreenSession.state.value.display
                    if (info != null && !directReady && !VirtualScreenFrameGate.paused) {
                        val next = readFrame()
                        if (VirtualScreenSession.state.value.display?.sessionId == info.sessionId) {
                            if (next != null) {
                                val view = surface
                                if (view != null) {
                                    view.setFrame(next.bitmap, info)
                                    lastFrameId = next.id
                                    hasFrame = true
                                    fresh = true
                                } else next.bitmap.recycle()
                            }
                        } else {
                            next?.bitmap?.recycle(); surface?.clearFrame(); hasFrame = false; lastFrameId = 0
                        }
                    }
                    // 手指刚动过就全速追帧，闲置时降频省电；旧帧不排队，永远画最新的那张。
                    // 刚出过新帧或手指刚动过就按档位全速追帧；延迟只补差值，不再叠加在耗时上（以前 = 耗时+延迟）。
                    val spent = android.os.SystemClock.elapsedRealtime() - startedAt
                    val base = if (fresh || VirtualScreenFrameGate.sinceInputMs() < 1200L) frameDelay else idleDelay
                    val budget = base - spent
                    if (budget > 0L) delay(budget)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        VirtualScreenFrameGate.viewerForeground = true
    }

    private data class Frame(val bitmap: Bitmap, val id: Long)

    private suspend fun readFrame(): Frame? {
        val pending = AtomicReference<Bitmap?>()
        return try {
            val result = withContext(Dispatchers.IO) {
                val frame = VirtualScreenSession.observeForViewer(this@VirtualScreenViewerActivity, lastFrameId)
                val json = JSONObject(frame.content)
                val bitmap = frame.images.firstOrNull()?.reference?.substringAfter("base64,")?.let { encoded ->
                    runCatching {
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply {
                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        })
                    }.getOrNull()
                }
                pending.set(bitmap)
                bitmap?.let { Frame(it, json.optLong("frameId")) }
            }
            pending.set(null)
            result
        } finally {
            pending.getAndSet(null)?.recycle()
        }
    }

    private fun sendInput(sessionId: String, args: JSONObject) {
        inputError = false
        // 走专用输入通道：单线程串行、拖动只留最新一跳，不再每个事件各起一个协程抢管道。
        VirtualScreenInputPump.submit(applicationContext, sessionId, args) { ok ->
            runOnUiThread { inputError = !ok }
        }
    }

    private fun sendText(sessionId: String) {
        val text = textDraft
        if (text.isEmpty() || textPending) return
        textPending = true
        inputError = false
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    VirtualScreenSession.inputForViewer(this@VirtualScreenViewerActivity, sessionId,
                        JSONObject().put("action", "text").put("text", text))
                }
                inputError = !JSONObject(result.content).optBoolean("ok")
                if (!inputError && VirtualScreenSession.state.value.display?.sessionId == sessionId) textDraft = ""
            } finally { textPending = false }
        }
    }

    @Composable
    private fun ViewerScreen() {
        val state by VirtualScreenSession.state.collectAsState()
        val display = state.display
        val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(display?.sessionId) { textDraft = ""; textInputVisible = false }
        LaunchedEffect(textInputVisible) {
            if (textInputVisible) focus.requestFocus() else keyboard?.hide()
        }
        EtaPreferenceTheme {
            Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
                SmallTopAppBar(
                    title = "",
                    navigationIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiuixBackButton(onClick = ::finish)
                            TaskIndicator(state, onClick = { showHistory = true })
                        }
                    },
                    actions = {
                        IconButton(enabled = display != null, onClick = { textInputVisible = !textInputVisible }) {
                            Icon(Icons.Outlined.Keyboard, stringResource(R.string.virtual_screen_text_toggle),
                                tint = if (textInputVisible) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { interactionEnabled = !interactionEnabled }) {
                            Icon(
                                imageVector = if (interactionEnabled) Icons.Outlined.TouchApp else Icons.Outlined.Block,
                                contentDescription = stringResource(if (interactionEnabled) R.string.virtual_screen_touch_on else R.string.virtual_screen_touch_off),
                                tint = if (interactionEnabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                )
            }) { padding ->
                WidePageContent { sidePadding ->
                    Column(
                        Modifier.fillMaxSize().horizontalCutoutPadding().padding(padding)
                            .padding(horizontal = sidePadding + 16.dp).navigationBarsPadding().imePadding(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            val aspect = display?.let { it.width.toFloat() / it.height } ?: (9f / 20f)
                            val width = minOf(maxWidth, maxHeight * aspect)
                            Box(Modifier.size(width, width / aspect).clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF101318)).virtualScreenControlBorder(state.isAgentControlling)) {
                                AndroidView(
                                    factory = { context ->
                                        VirtualScreenSurfaceView(context).also {
                                            it.onInput = ::sendInput
                                            it.contentDescription = context.getString(R.string.virtual_screen_touch_description)
                                            val app = context.applicationContext
                                            it.onHostSurface = { host ->
                                                VirtualScreenHostSurfaces.attach("viewer", host)
                                                val live = VirtualScreenHostSurfaces.live && host != null
                                                it.post {
                                                    it.setDirectMode(live)
                                                    directReady = live
                                                    if (live) hasFrame = true
                                                }
                                                if (VirtualScreenHostSurfaces.live) {
                                                    Thread {
                                                        runCatching { VirtualScreenSession.notifyHostSurface(app) }
                                                    }.apply { isDaemon = true }.start()
                                                }
                                            }
                                            surface = it
                                        }
                                    },
                                    update = { it.touchEnabled = interactionEnabled; it.setDisplayInfo(display) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (!hasFrame) {
                                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(if (display != null) R.string.virtual_screen_loading else R.string.virtual_screen_idle),
                                            color = Color.White, style = MiuixTheme.textStyles.headline2)
                                        Text(stringResource(if (display != null) R.string.virtual_screen_viewer_empty else R.string.virtual_screen_empty_hint),
                                            color = Color(0xFFB7BEC8), style = MiuixTheme.textStyles.body2)
                                    }
                                }
                            }
                        }
                        if (inputError) Text(stringResource(R.string.virtual_screen_input_failed),
                            color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                        sessionError?.let { message ->
                            Text("虚拟屏启动失败：$message\n" + VscrDiag.tailText(),
                                color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                        }
                        if (textInputVisible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextField(value = textDraft, onValueChange = { if (it.length <= 20000) textDraft = it },
                                label = stringResource(R.string.virtual_screen_text_placeholder), enabled = !textPending,
                                modifier = Modifier.weight(1f).focusRequester(focus), maxLines = 3)
                            IconButton(enabled = display != null && textDraft.isNotEmpty() && !textPending,
                                onClick = {
                                    keyboard?.hide()
                                    focusManager.clearFocus()
                                    display?.let { sendText(it.sessionId) }
                                }) {
                                Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.virtual_screen_text_insert),
                                    tint = if (display != null && textDraft.isNotEmpty() && !textPending)
                                        MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(22.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf("BACK" to R.string.action_back, "HOME" to R.string.virtual_screen_home, "MENU" to R.string.virtual_screen_menu).forEach { (key, label) ->
                                IconButton(enabled = display != null && interactionEnabled, onClick = {
                                    display?.let { sendInput(it.sessionId, JSONObject().put("action", "key").put("button", key)) }
                                }, modifier = Modifier.size(40.dp)) { NavigationKey(key, stringResource(label)) }
                            }
                        }
                    }
                }
            }
            VirtualScreenAppConflictDialog()
            EtaWindowDialog(show = showHistory, title = stringResource(R.string.virtual_screen_history), onDismissRequest = { showHistory = false }) {
                if (state.operations.isEmpty()) Text(stringResource(R.string.virtual_screen_history_empty), style = MiuixTheme.textStyles.body2)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.operations.asReversed(), key = { it.id }) { operation ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(operationLabel(operation.name), color = MiuixTheme.colorScheme.onSurface, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(operation.timestamp)) + " · " +
                                    stringResource(if (operation.manual) R.string.virtual_screen_history_manual else R.string.virtual_screen_history_agent),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                            }
                            Text(stringResource(if (operation.success) R.string.virtual_screen_operation_success else R.string.virtual_screen_operation_failed),
                                color = if (operation.success) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                                style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        VirtualScreenFrameGate.viewerForeground = false
        surface?.clearFrame(); hasFrame = false; lastFrameId = 0
        VirtualScreenFloatWindow.exitFullScreen()
        super.onPause()
    }
}

@Composable
private fun TaskIndicator(state: VirtualScreenViewerState, onClick: () -> Unit) {
    val color = when (state.taskPhase) {
        VirtualScreenTaskPhase.RUNNING -> MiuixTheme.colorScheme.primary
        VirtualScreenTaskPhase.FAILED -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val label = when (state.taskPhase) {
        VirtualScreenTaskPhase.RUNNING -> R.string.virtual_screen_live
        VirtualScreenTaskPhase.COMPLETED -> R.string.virtual_screen_completed
        VirtualScreenTaskPhase.FAILED -> R.string.virtual_screen_failed
        VirtualScreenTaskPhase.STOPPED -> R.string.virtual_screen_stopped
        VirtualScreenTaskPhase.IDLE -> R.string.virtual_screen_ready
    }
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.1f))
        .clickable(onClickLabel = stringResource(R.string.virtual_screen_history), onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(stringResource(label), color = color, style = MiuixTheme.textStyles.footnote1)
    }
}

@Composable
private fun operationLabel(name: String): String = when (name) {
    "home" -> stringResource(R.string.virtual_screen_home)
    "menu", "switch_task" -> stringResource(R.string.virtual_screen_menu)
    "back" -> stringResource(R.string.action_back)
    "enter" -> toolDisplayName("press_key")
    "text" -> toolDisplayName("input_text")
    "create" -> stringResource(R.string.virtual_screen_started)
    else -> toolDisplayName(name)
}

@Composable
private fun NavigationKey(key: String, description: String) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(Modifier.size(16.dp).then(Modifier.semantics { contentDescription = description })) {
        val stroke = Stroke(1.8.dp.toPx())
        when (key) {
            "BACK" -> drawPath(Path().apply { moveTo(size.width * 0.75f, 0f); lineTo(size.width * 0.1f, size.height / 2)
                lineTo(size.width * 0.75f, size.height); close() }, color, style = stroke)
            "HOME" -> drawCircle(color, size.minDimension / 2 - stroke.width, style = stroke)
            else -> drawRoundRect(color, topLeft = Offset(stroke.width, stroke.width),
                size = Size(size.width - 2 * stroke.width, size.height - 2 * stroke.width),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
        }
    }
}
