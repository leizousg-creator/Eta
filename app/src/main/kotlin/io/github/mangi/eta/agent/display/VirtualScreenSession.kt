package io.github.mangi.eta.agent.display

import android.content.Context
import android.util.Base64
import android.os.SystemClock
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Runtime owns the display; viewer inputs are serialized with its tools. */
internal object VirtualScreenSession {
    private val lock = Any()
    private val deadlines = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(
            r,
            "eta-display-deadline"
        ).apply { isDaemon = true }
    }
    private val session = AtomicReference<Session?>()
    private val viewerState = MutableStateFlow(VirtualScreenViewerState())
    val state = viewerState.asStateFlow()
    private val gestureIds = AtomicLong()
    private val operationIds = AtomicLong()
    private val cleanup = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eta-display-cleanup").apply {
            isDaemon = true
        }
    }

    private class Session(
        val context: Context,
        val owner: String,
        val process: java.lang.Process,
        val input: BufferedWriter,
        val output: BufferedReader,
        var width: Int,
        var height: Int,
        val allowOff: Boolean,
        runId: String?,
    ) {
        val id = UUID.randomUUID().toString()
        var displayId: Int = -1
        val closed = AtomicBoolean()
        val runLease = VirtualScreenRunLease(runId)
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            session.compareAndSet(this, null)
            viewerState.update {
                if (it.display?.sessionId != id) it else it.copy(
                    display = null, gesture = null, activeRunId = null,
                    taskPhase = if (it.taskPhase == VirtualScreenTaskPhase.RUNNING) VirtualScreenTaskPhase.STOPPED else it.taskPhase,
                )
            }
            VirtualScreenNotification.cancel(context, id)
            AgentExecutionService.release("virtual-screen-$id")
            // 不等待 execute 持有的锁或正在读取的帧；主线程取消必须立即返回。
            process.destroy()
            cleanup.execute {
                runCatching { process.outputStream.close() }
                runCatching { input.close() }
                runCatching { output.close() }
            }
        }
    }

    fun isActive(): Boolean = session.get()?.let { !it.closed.get() && it.process.isAlive } == true

    fun isOwnedBy(owner: String): Boolean = session.get()?.let {
        it.owner == owner && !it.closed.get() && it.process.isAlive
    } == true

    /** Idle displays may be replaced by another conversation; an executing run keeps exclusive ownership. */
    fun prepareForRun(owner: String, runId: String): Boolean = synchronized(lock) {
        val current = session.get() ?: return@synchronized true
        if (current.closed.get() || !current.process.isAlive) {
            current.close()
            return@synchronized true
        }
        if (current.owner == owner) {
            val acquired = current.runLease.acquire(runId)
            if (acquired && viewerState.value.activeRunId != runId) {
                viewerState.update { if (it.display?.sessionId == current.id) it.beginRun(runId) else it }
                updateIdleTimeout()
            }
            return@synchronized acquired
        }
        if (!current.runLease.retireIdle()) return@synchronized false
        current.close()
        true
    }

    fun releaseRun(owner: String, runId: String, retain: Boolean, cancelled: Boolean = false) = synchronized(lock) {
        val current = session.get()?.takeIf { it.owner == owner } ?: return@synchronized
        if (!current.runLease.release(runId, retain)) return@synchronized
        viewerState.update { if (it.display?.sessionId == current.id) it.finishRun(runId, retain, cancelled) else it }
        if (!retain) current.close() else updateIdleTimeout()
    }

    fun updateIdleTimeout() = synchronized(lock) {
        val current = session.get() ?: return@synchronized
        val minutes = runBlocking { SettingsDataStore.settings() }.virtualScreenIdleTimeoutMinutes
        val fps = io.github.mangi.eta.data.model.VirtualScreenFrameRate.normalize(
            runBlocking { SettingsDataStore.settings() }.virtualScreenFrameRate)
        execute(current.context, current.owner, JSONObject().put("action", "lifecycle")
            .put("idleTimeoutMinutes", minutes).put("activeRun", viewerState.value.activeRunId != null)
            .put("frameIntervalMs", io.github.mangi.eta.data.model.VirtualScreenFrameRate.intervalMs(fps))
            .put("jpegQuality", io.github.mangi.eta.data.model.VirtualScreenFrameRate.jpegQuality(fps)))
        Unit
    }

    fun recordOperation(owner: String, runId: String?, name: String, success: Boolean, manual: Boolean = false) {
        val current = session.get()?.takeIf { it.owner == owner && !it.closed.get() } ?: return
        val operation = VirtualScreenOperation(operationIds.incrementAndGet(), name, System.currentTimeMillis(), success, manual)
        viewerState.update {
            if (it.display?.sessionId != current.id || runId != null && it.activeRunId != runId) it else it.record(operation)
        }
    }

    fun execute(
        context: Context,
        owner: String,
        args: JSONObject,
        isCancelled: () -> Boolean = { false },
        runId: String? = null,
        appRestartApproved: Boolean = false,
    ): AgentModelClient.ToolResult = synchronized(lock) {
        try {
            check(!isCancelled()) { "DISPLAY_CANCELLED" }
            require(android.os.Build.VERSION.SDK_INT >= 34) { "DEVICE_UNSUPPORTED" }
            val settings = runBlocking { SettingsDataStore.settings() }
            require(settings.virtualScreenEnabled) { "VIRTUAL_SCREEN_DISABLED" }
            if (!RootAccess.isGranted) {
                VscrDiag.line("RootAccess 未授权，主动申请一次")
                runBlocking { runCatching { RootAccess.request(context).join() } }
                VscrDiag.line("RootAccess 申请结果 granted=" + RootAccess.isGranted)
            }
            require(RootAccess.isGranted) { "ROOT_REQUIRED" }
            val action = args.getString("action")
            if (action == "launch" && args.optBoolean("restartApp")) {
                require(appRestartApproved || settings.virtualScreenAutoRestartApps) { "APP_RESTART_PERMISSION_REQUIRED" }
            }
            if (action == "create") {
                require(owner.isNotBlank()) { "DISPLAY_OWNER_REQUIRED" }
                require(session.get() == null) { "DISPLAY_ALREADY_ACTIVE" }
                val allowOff = args.optBoolean("allowScreenOff", false)
                require(!allowOff || settings.virtualScreenOffEnabled) { "SCREEN_OFF_PERMISSION_REQUIRED" }
                val profile = VirtualScreenProfile.from(context)
                val width = bounded(args, "width", 320, VirtualScreenProfile.MAX_WIDTH, profile.width)
                val height = bounded(args, "height", 480, VirtualScreenProfile.MAX_HEIGHT, profile.height)
                val density = bounded(args, "density", 120, VirtualScreenProfile.MAX_DENSITY, profile.density)
                io.github.mangi.eta.core.EtaTrace.log(
                    "vscr",
                    "helper 启动 runId=$runId owner=$owner ${width}x$height" +
                        " density=$density allowOff=$allowOff",
                )
                HelperExitLog.started()
                VscrDiag.line("helper 启动 " + width + "x" + height + " d=" + density + " owner=" + owner)
                val apk = context.applicationInfo.sourceDir
                fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
                val process = ProcessBuilder(
                    "su",
                    "-c",
                    "CLASSPATH=${quote(apk)} app_process /system/bin io.github.mangi.eta.agent.display.RootDisplayCommandMain"
                ).start()
                VscrDiag.line("helper 进程已创建")
                Thread {
                    runCatching {
                        // 持续排空，同时把诊断文本留给 HelperExitLog；它只收短行，
                        // 超长行与疑似 base64 数据一律丢弃，不会带出画面或私有数据。
                        process.errorStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                HelperExitLog.stderr(line)
                                VscrDiag.line("helper stderr: " + line)
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
                val input = process.outputStream.bufferedWriter()
                val output = process.inputStream.bufferedReader()
                val current = Session(context.applicationContext, owner, process, input, output, width, height, allowOff, runId)
                check(session.compareAndSet(null, current))
                Thread {
                    val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
                    // 退出码是判断 native 崩溃还是被系统回收的唯一线索，必须留下。
                    io.github.mangi.eta.core.EtaTrace.log("vscr", "helper 退出 exitCode=$exitCode")
                    HelperExitLog.finished(exitCode)
                    VscrDiag.line("helper 退出 exitCode=" + exitCode)
                    current.close()
                }.apply { name = "eta-display-exit"; isDaemon = true; start() }
                if (!AgentExecutionService.acquire(context, "virtual-screen-${current.id}", onStop = current::close)) {
                    current.close(); error("DISPLAY_LIFECYCLE_UNAVAILABLE")
                }
                if (isCancelled() || current.closed.get()) {
                    AgentExecutionService.release("virtual-screen-${current.id}")
                    current.close(); error("DISPLAY_CANCELLED")
                }
                val deadline = deadlines.schedule({ current.close() }, 15, TimeUnit.SECONDS)
                val response = try {
                    input.write(
                        JSONObject().put("action", "create").put("width", width)
                            .put("height", height).put("density", density)
                            .put("sessionId", current.id)
                            .put("selfPackage", context.packageName)
                            .put("surfaceToken", VirtualScreenHostSurfaces.token)
                            .put("idleTimeoutMinutes", settings.virtualScreenIdleTimeoutMinutes)
                            .put("refreshRate", settings.virtualScreenFrameRate)
                            .put("frameIntervalMs", io.github.mangi.eta.data.model.VirtualScreenFrameRate.intervalMs(settings.virtualScreenFrameRate))
                            .put("jpegQuality", io.github.mangi.eta.data.model.VirtualScreenFrameRate.jpegQuality(settings.virtualScreenFrameRate))
                            .put("activeRun", runId != null)
                            .put("allowScreenOff", allowOff).toString()
                    ); input.newLine(); input.flush()
                    VscrDiag.line("create 已发送，等待 helper 响应")
                    readResponse(output)
                } catch (error: Exception) {
                    VscrDiag.line("create 等待失败: " + error.message)
                    current.close(); throw error
                } finally {
                    deadline.cancel(false)
                }
                VscrDiag.line("helper 响应: " + response.toString().take(300))
                if (!response.optBoolean("ok") || response.optInt("displayId") <= 0 || current.closed.get()) {
                    current.close()
                    error(response.optString("code", "ROOT_DISPLAY_UNAVAILABLE"))
                }
                current.displayId = response.getInt("displayId")
                // helper 用宿主面建屏成功才允许界面切直通；否则保持整帧管线。
                VirtualScreenHostSurfaces.live = response.optBoolean("direct")
                viewerState.value = VirtualScreenViewerState(
                    display = VirtualDisplayInfo(current.id, owner, current.displayId, width, height, density = density),
                    lastAction = "create",
                    taskPhase = if (runId == null) VirtualScreenTaskPhase.IDLE else VirtualScreenTaskPhase.RUNNING,
                    activeRunId = runId,
                )
                VirtualScreenNotification.show(context, current.id)
                if (current.closed.get()) {
                    viewerState.update { if (it.display?.sessionId == current.id) VirtualScreenViewerState() else it }
                    VirtualScreenNotification.cancel(context, current.id)
                    error("DISPLAY_CANCELLED")
                }
                return@synchronized AgentModelClient.ToolResult(response.toString())
            }
            val current = session.get() ?: error("NO_VIRTUAL_SCREEN")
            require(owner == current.owner) { "DISPLAY_OWNER_MISMATCH" }
            require(!current.closed.get() && current.process.isAlive) { "DISPLAY_GONE" }
            require(!current.allowOff || settings.virtualScreenOffEnabled) { "SCREEN_OFF_PERMISSION_REQUIRED" }
            val timeout = deadlines.schedule({ current.close() }, 15, TimeUnit.SECONDS)
            val result = try {
                publishGesture(current, args)
                current.input.write(args.toString()); current.input.newLine(); current.input.flush()
                readResponse(current.output)
            } finally {
                timeout.cancel(false)
            }
            if (result.optBoolean("ok")) {
                // 横屏应用会让 Root 进程把 display 换成横屏尺寸，这里同步尺寸供小窗、坐标系与截图共用。
                val nextWidth = result.optInt("width", 0)
                val nextHeight = result.optInt("height", 0)
                if (nextWidth > 0 && nextHeight > 0) {
                    current.width = nextWidth
                    current.height = nextHeight
                }
                if (action in setOf("observe", "probe", "launch", "switch_task")) {
                    viewerState.update { state -> state.copy(display = state.display?.let { info ->
                        if (info.sessionId != current.id) info else info.copy(
                            width = current.width,
                            height = current.height,
                            focusedPackage = when (action) {
                                "probe" -> result.optString("packageName")
                                "launch", "switch_task" -> ""
                                else -> info.focusedPackage
                            },
                        )
                    }) }
                }
            }
            if (action !in setOf("observe", "probe")) {
                viewerState.update { it.copy(lastAction = action) }
            }
            if (action == "close") {
                current.close(); session.compareAndSet(current, null)
            }
            val encoded = result.optString("image")
            result.remove("image")
            val images = if (encoded.isNotBlank() && result.optBoolean("ok")) listOf(
                AgentModelClient.ModelImage(
                    "data:image/jpeg;base64,$encoded",
                    "image/jpeg",
                    encoded.length * 3 / 4,
                    current.width,
                    current.height,
                    "virtual_display",
                    true
                )
            ) else emptyList()
            AgentModelClient.ToolResult(result.toString(), images, sensitive = true)
        } catch (error: Exception) {
            VscrDiag.line("execute 失败 action=" + args.optString("action") + " 原因=" + error.message)
            session.get()?.let { current ->
                if (current.closed.get() || !current.process.isAlive ||
                    current.owner == owner && error.message in setOf("ROOT_REQUIRED", "VIRTUAL_SCREEN_DISABLED", "SCREEN_OFF_PERMISSION_REQUIRED")) {
                    current.close()
                    session.compareAndSet(current, null)
                }
            }
            AgentModelClient.ToolResult(
                JSONObject().put("ok", false).put(
                    "code",
                    if (error is IllegalArgumentException || error is IllegalStateException) error.message
                        ?: "VIRTUAL_SCREEN_FAILED" else "VIRTUAL_SCREEN_FAILED"
                )
                    .put(
                        "message",
                        "虚拟屏操作未执行；不会回退到主屏。请确认 Root、虚拟屏开关和设备接口。"
                    ).toString()
            )
        }
    }

    /** A′ 直通：宿主面变了（查看页/小窗重建、互切），让 helper 立刻重新取面。 */
    fun notifyHostSurface(context: Context) = synchronized(lock) {
        val current = session.get() ?: return@synchronized
        if (current.closed.get()) return@synchronized
        val result = execute(context, current.owner, JSONObject().put("action", "host_surface"))
        if (JSONObject(result.content).optBoolean("direct")) VirtualScreenHostSurfaces.live = true
    }

    /** 只探尺寸与前台包，不拉整帧：直通模式下小窗靠它跟上虚拟屏的横竖屏切换。 */
    fun refreshForViewer(context: Context): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()
            ?: return@synchronized AgentModelClient.ToolResult("{\"ok\":false,\"code\":\"NO_VIRTUAL_SCREEN\"}")
        execute(context, current.owner, JSONObject().put("action", "probe"))
    }

    fun observeForViewer(context: Context, afterFrameId: Long = 0): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()
            ?: return@synchronized AgentModelClient.ToolResult("{\"ok\":false,\"code\":\"NO_VIRTUAL_SCREEN\"}")
        execute(context, current.owner, JSONObject().put("action", "observe").put("afterFrameId", afterFrameId))
    }

    fun inputForViewer(context: Context, sessionId: String, args: JSONObject): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()
        if (current == null || current.id != sessionId || args.optString("action") !in setOf("tap", "swipe", "long_press", "back", "key", "switch_task", "text", "touch", "ime")) {
            return@synchronized failure("STALE_VIRTUAL_SCREEN")
        }
        // 直通拖动每秒上百个事件：只有按下和抬起值得记一条历史。
        val liveMove = args.optString("action") == "touch" && args.optString("phase") in setOf("down", "move")
        if (!liveMove) {
            viewerState.update { state -> state.copy(display = state.display?.let {
                it.copy(manualInputGeneration = it.manualInputGeneration + 1)
            }) }
        }
        execute(context, current.owner, args).also { result ->
            if (!liveMove) {
                val name = if (args.optString("action") == "key") args.optString("button").lowercase() else args.optString("action")
                recordOperation(current.owner, null, name, JSONObject(result.content).optBoolean("ok"), manual = true)
            }
        }
    }

    fun tasksForViewer(context: Context, sessionId: String): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()?.takeIf { it.id == sessionId } ?: return@synchronized failure("STALE_VIRTUAL_SCREEN")
        execute(context, current.owner, JSONObject().put("action", "tasks"))
    }

    /** 手动在小窗里点开某个应用：只在 display 内启动，绝不把主屏任务迁进来。 */
    fun launchForViewer(context: Context, sessionId: String, component: String): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()?.takeIf { it.id == sessionId } ?: return@synchronized failure("STALE_VIRTUAL_SCREEN")
        execute(context, current.owner, JSONObject().put("action", "launch").put("component", component)).also { result ->
            recordOperation(current.owner, null, "launch", JSONObject(result.content).optBoolean("ok"), manual = true)
        }
    }

    /** 在 display 内打开 Eta 自带导航页；mode 用 HOME / MENU / APPS。 */
    fun homeForViewer(context: Context, sessionId: String, mode: String): AgentModelClient.ToolResult = synchronized(lock) {
        val current = session.get()?.takeIf { it.id == sessionId } ?: return@synchronized failure("STALE_VIRTUAL_SCREEN")
        val action = mode.takeIf { it in setOf("HOME", "MENU", "APPS") } ?: return@synchronized failure("VIRTUAL_ACTION_UNSUPPORTED")
        execute(context, current.owner, JSONObject().put("action", "key").put("button", action))
    }

    /** Probe the Root process while holding the same lock as manual input and other tools. */
    fun withDisplay(
        context: Context,
        owner: String,
        isCancelled: () -> Boolean = { false },
        block: (VirtualDisplayInfo) -> AgentModelClient.ToolResult,
    ): AgentModelClient.ToolResult = synchronized(lock) {
        val probe = execute(context, owner, JSONObject().put("action", "probe"), isCancelled)
        if (!JSONObject(probe.content).optBoolean("ok")) return@synchronized probe
        val info = viewerState.value.display ?: return@synchronized failure("NO_VIRTUAL_SCREEN")
        block(info.copy(focusedPackage = JSONObject(probe.content).optString("packageName")))
    }

    fun showNodeGesture(info: VirtualDisplayInfo, action: String, x: Int, y: Int, durationMs: Int = 500, endX: Int = x, endY: Int = y) {
        val current = session.get()?.takeIf { it.id == info.sessionId } ?: return
        publishGesture(current, JSONObject().put("action", action).put("x", x).put("y", y)
            .put("x1", x).put("y1", y).put("x2", endX).put("y2", endY).put("durationMs", durationMs))
    }

    private fun publishGesture(current: Session, args: JSONObject) {
        val action = args.optString("action")
        if (action !in setOf("tap", "long_press", "swipe")) return
        val x = args.optInt(if (action == "swipe") "x1" else "x", -1)
        val y = args.optInt(if (action == "swipe") "y1" else "y", -1)
        val endX = if (action == "swipe") args.optInt("x2", -1) else x
        val endY = if (action == "swipe") args.optInt("y2", -1) else y
        if (x !in 0 until current.width || y !in 0 until current.height || endX !in 0 until current.width || endY !in 0 until current.height) return
        viewerState.update { state -> state.copy(gesture = VirtualScreenGesture(
            gestureIds.incrementAndGet(), current.id, action, x, y, endX, endY,
            args.optInt("durationMs", 500), SystemClock.elapsedRealtime(),
        )) }
    }

    private fun failure(code: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false).put("code", code).toString(), sensitive = true,
    )

    fun closeOwner(owner: String) {
        session.get()?.takeIf { it.owner == owner }?.let { current ->
            if (session.compareAndSet(current, null)) current.close()
        }
    }

    fun revokePermission() {
        session.getAndSet(null)?.close()
    }

    private fun readResponse(output: BufferedReader): JSONObject {
        repeat(20) {
            val line = output.readLine() ?: error("ROOT_DISPLAY_DISCONNECTED")
            require(line.length <= 2_800_000) { "DISPLAY_OUTPUT_TOO_LARGE" }
            if (line.startsWith("ETA_DISPLAY_RESULT:")) return JSONObject(line.removePrefix("ETA_DISPLAY_RESULT:"))
        }
        error("DISPLAY_OUTPUT_INVALID")
    }

    private fun bounded(args: JSONObject, name: String, min: Int, max: Int, default: Int): Int {
        if (!args.has(name)) return default
        val value = args.get(name)
        require(
            value is Number && value.toDouble() == value.toInt()
                .toDouble() && value.toInt() in min..max
        ) { "INVALID_DISPLAY_DIMENSIONS" }
        return value.toInt()
    }
}
