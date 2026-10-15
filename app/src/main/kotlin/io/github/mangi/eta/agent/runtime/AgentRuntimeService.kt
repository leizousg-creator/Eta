package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.overlay.AgentOverlayRunningSession
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import io.github.mangi.eta.ui.MainActivity
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.window.OnBackInvokedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.display.VirtualScreenRoutingPolicy
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenNotification
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayGlow
import io.github.mangi.eta.agent.overlay.ScreenCornerRadii
import io.github.mangi.eta.agent.overlay.AgentOverlayCapsule
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agent-result-io") }
    private val serviceMessenger = Messenger(IncomingHandler())
    private lateinit var islandBridge: AgentRuntimeIslandBridge

    /** 进行中的会话，按 runId 索引；同一时刻最多 [MAX_CONCURRENT_RUNS] 个。 */
    private val sessions = linkedMapOf<String, AgentRuntimeSession>()

    private fun sessionFor(runId: String): AgentRuntimeSession? = sessions[runId]

    private data class RunningSessionInfo(val runId: String, val conversationId: String, val title: String)

    /** 运行中会话的展示信息：胶囊展开后点一条就能跳回对应会话。 */
    private val runningSessionInfo = linkedMapOf<String, RunningSessionInfo>()

    /** 已经跑完但仍要在胶囊里可点的会话（按完成顺序，最多 SESSION_LIST_LIMIT 条）。 */
    private val recentSessionInfo = linkedMapOf<String, RunningSessionInfo>()

    /** 按当前 sessions 同步胶囊用的运行中清单（多会话并发时列表会变长）。 */
    private fun syncRunningSessions() {
        runningSessionInfo.keys.retainAll(sessions.keys.toSet())
        val list = mutableListOf<AgentOverlayRunningSession>()
        sessions.keys.forEach { runId ->
            runningSessionInfo[runId]?.let { info ->
                list += AgentOverlayRunningSession(
                    runId = info.runId,
                    conversationId = info.conversationId,
                    title = info.title,
                    running = true,
                )
            }
        }
        // 运行中的排前面，后面补最近完成的会话，用户随时能点回去。
        recentSessionInfo.values.toList().asReversed().forEach { info ->
            if (list.size >= SESSION_LIST_LIMIT) return@forEach
            // 同一个会话只留一条：它正在跑的时候，不再显示它的「已完成」旧记录。
            if (list.any { it.conversationId == info.conversationId }) return@forEach
            list += AgentOverlayRunningSession(
                runId = info.runId,
                conversationId = info.conversationId,
                title = info.title,
                running = false,
            )
        }
        val trimmed = list.take(SESSION_LIST_LIMIT)
        if (state.value.runningSessions == trimmed) return
        state.value = state.value.copy(
            runningSessions = trimmed,
            finishedBadge = unreadFinishedCount,
            recentOpenedSessions = recentOpenedState.value.take(RECENT_OPENED_SHOWN),
            unreadSessions = unreadSessionState.value,
        )
    }

    /** 会话结束后把它记进「最近会话」，胶囊展开后仍可点回去。 */
    private fun rememberFinishedSession(runId: String) {
        val info = runningSessionInfo[runId] ?: return
        recentSessionInfo.remove(runId)
        recentSessionInfo[runId] = info
        while (recentSessionInfo.size > SESSION_LIST_LIMIT) {
            val oldest = recentSessionInfo.keys.firstOrNull() ?: break
            recentSessionInfo.remove(oldest)
        }
        // 完成但还没被打开过：进「最新回复」，用户点一次就算看过。
        info.conversationId.takeIf { it.isNotBlank() }?.let { conversationId ->
            unreadSessionIds.remove(conversationId)
            unreadSessionIds.add(conversationId)
            while (unreadSessionIds.size > UNREAD_SESSION_KEEP) {
                val oldestUnread = unreadSessionIds.firstOrNull() ?: break
                unreadSessionIds.remove(oldestUnread)
            }
            refreshUnreadSessions()
        }
    }

    /** 刷新「最新回复」栏内容（标题取运行期记下的会话标题）。 */
    private fun refreshUnreadSessions() {
        unreadSessionState.value = unreadSessionIds.toList().asReversed().map { id ->
            val title = recentSessionInfo.values
                .firstOrNull { it.conversationId == id }
                ?.title
                ?.takeIf { it.isNotBlank() }
            AgentOverlayRunningSession(
                runId = id,
                conversationId = id,
                title = title ?: "会话",
                running = false,
            )
        }
    }

    /** 从悬浮胶囊跳回某个会话：复用 MainActivity 已有的“打开指定会话”入口。 */
    private fun openConversation(conversationId: String) {
        noteRecentOpened(conversationId)
        if (unreadSessionIds.remove(conversationId)) refreshUnreadSessions()
        if (conversationId.isBlank()) return
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                    .putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            capsuleExpanded.value = false
            // 点开就等于看到了：清掉完成提示，并把这条从「最近会话」里移除。
            unreadFinished = false
            unreadFinishedCount = (unreadFinishedCount - 1).coerceAtLeast(0)
            // 列表保持"最近三条"常驻：点开过只是清未读角标，不把会话从列表里删掉。
            if (runningSessionCount() == 0) {
                if (capsulePinned) {
                    state.value = AgentOverlayState(status = AgentOverlayStatus.Idle)
                    ensureOverlayVisible()
                } else {
                    dismissAndStop()
                }
            }
            // 重建列表：刚点开的那条已经移出，其余最近会话保留。
            syncRunningSessions()
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("overlay_open_conversation_failed") {
                "Overlay open conversation failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /** 仍在运行的会话数（含正在收尾的）。 */
    private fun runningSessionCount(): Int = sessions.values.count { session -> !session.isTerminal }
    private var startRequestGeneration = 0L
    /** 并发准备槽位：每个 run 各占一个，后来的请求不会把前一个顶掉。 */
    private val pendingStartRequests = linkedMapOf<String, PendingStartRequest>()

    private data class PendingStartRequest(
        val generation: Long,
        val incoming: AgentRuntimeWire.IncomingRunRequest,
        val replyTo: Messenger?,
    )

    private var windowManager: WindowManager? = null
    private var glowView: ComposeView? = null
    private var capsuleView: ComposeView? = null
    private var resultCardView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var capsuleParams: WindowManager.LayoutParams? = null

    /** 收起态最后停留的位置（拖动/贴边后更新），展开再收回时回到这里。 */
    private var capsuleCollapsedX = Int.MIN_VALUE
    private var capsuleCollapsedY = Int.MIN_VALUE

    /** 上次保存的位置（重启后从设置读回来）。 */
    private var savedCapsuleX = -1
    private var savedCapsuleY = -1

    /** 小胶囊当前所在位置（像素，窗口坐标），给胶囊界面用来对齐展开面板。 */
    private val capsuleOffsetX = mutableStateOf(0)
    private val capsuleOffsetY = mutableStateOf(0)

    /** 窗口是不是全屏展开态：全屏时才需要把面板摆回小胶囊的位置。 */
    private val capsuleFullScreen = mutableStateOf(false)

    /** 最近打开过的会话 id（最新在前，内部最多记 5 条，面板上只显示 3 条）。 */
    private val recentOpenedIds = ArrayDeque<String>()
    private val recentOpenedState = mutableStateOf<List<AgentOverlayRunningSession>>(emptyList())
    /** 完成但还没打开过的会话（最新在前）：面板「最新回复」栏用。 */
    private val unreadSessionIds = LinkedHashSet<String>()
    private val unreadSessionState = mutableStateOf<List<AgentOverlayRunningSession>>(emptyList())
    private var resultCardParams: WindowManager.LayoutParams? = null
    private var resultCardBack: Pair<OnBackInvokedDispatcher, OnBackInvokedCallback>? = null

    /** 浮层与执行通知共用同一份运行状态；写入只发生在主线程。 */
    private val state = object {
        private val holder = mutableStateOf(AgentOverlayState.Initial)
        var value: AgentOverlayState
            get() = holder.value
            set(next) {
                holder.value = next
                AgentExecutionService.updateRunStatus(
                    this@AgentRuntimeService,
                    next.status.takeIf { runningSessionCount() > 0 },
                )
            }
    }
    private val capsuleExpanded = mutableStateOf(false)
    private var hasExecutedForegroundTool = false
    /** 结果卡片"回到 Eta"打开的会话；只有 App 内发起的 run 才有，系统助手入口为 null。 */
    private var resultConversationId: String? = null
    private val supplementsLock = Any()
    private val activeSupplements = mutableListOf<AgentUiHandoffPayload.Supplement>()
    private var nextSupplementIndex = 1
    @Volatile
    private var lastCompletedRunContext: CompletedRunContext? = null
    private val hideToken = Any()

    /** 常驻开关：打开 Eta 后胶囊一直在，不随任务结束消失。 */
    private var capsulePinned = false

    /** 完成后未读：胶囊保留「已完成」提示，直到用户打开 Eta。 */
    private var unreadFinished = false

    /** 未读完成条数：小胶囊角标数字，看过即减、回到 Eta 归零。 */
    private var unreadFinishedCount = 0

    override fun onCreate() {
        super.onCreate()
        islandBridge = AgentRuntimeIslandBridge(this, AndroidAgentLogger, ::cancelRun)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PIN_CAPSULE -> {
                capsulePinned = true
                ensureOverlayVisible()
                if (runningSessionCount() == 0) {
                    state.value = AgentOverlayState(status = AgentOverlayStatus.Idle)
                    // 空闲态也把最近会话补回列表，点开胶囊就能切回去。
                    syncRunningSessions()
                }
            }
            ACTION_UNPIN_CAPSULE -> {
                capsulePinned = false
                if (runningSessionCount() == 0) dismissAndStop()
            }
            ACTION_CLEAR_READ -> {
                unreadFinished = false
                unreadFinishedCount = 0
                unreadSessionIds.clear()
                refreshUnreadSessions()
                // 回到 Eta 只清未读角标；「最近三条会话」继续留着，方便随时点回去。
                if (runningSessionCount() == 0) {
                    if (capsulePinned) {
                        state.value = AgentOverlayState(status = AgentOverlayStatus.Idle)
                        ensureOverlayVisible()
                    } else {
                        dismissAndStop()
                    }
                }
            }
            else -> {
                val keepAlive = intent?.action == ACTION_KEEP_ALIVE && sessions.isNotEmpty()
                // 并发保护：还有会话在跑就别停自己，否则会把并行中的任务一起取消。
                if (!keepAlive && !capsulePinned && !unreadFinished && runningSessionCount() == 0) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (runningSessionCount() > 0) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        // 经验复盘由自己的执行引用持有；文字任务关闭入口服务后仍可完成。
        startRequestGeneration++
        pendingStartRequests.values.toList().forEach { pending ->
            pending.incoming.close()
            sendRequestIngestedTo(pending.replyTo, pending.incoming.request.runId)
            sendResultTo(
                pending.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = pending.incoming.request.runId,
                    ok = false,
                    content = "",
                    error = "Agent Runtime 服务已停止",
                ),
            )
        }
        pendingStartRequests.clear()
        sessions.values.forEach { session -> session.cancel("Agent Runtime 服务已停止") }
        sessions.clear()
        islandBridge.close()
        resultIo.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure("Agent Runtime 请求缺少消息体", msg.replyTo)
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime 请求格式无效", msg.replyTo)
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.operation != AgentRuntimeWire.OP_COMPACT && request.prompt.isBlank() && incoming.images.isEmpty() && !incoming.hasDeferredPrompt)) {
                        incoming.close()
                        finishWithFailure("Agent Runtime 请求缺少 runId 或用户输入", msg.replyTo)
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo)
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_PAUSE -> requestPause()
                AgentRuntimeWire.MSG_RESUME -> requestResume()
                AgentRuntimeWire.MSG_STEER -> {
                    val text = msg.data?.getString(AgentRuntimeWire.KEY_PROMPT).orEmpty().trim()
                    val conversationId = msg.data
                        ?.getString(AgentRuntimeWire.KEY_CONVERSATION_ID)
                        .orEmpty()
                    if (text.isNotBlank()) requestSupplement(text, conversationId)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    dispatchResultIo { AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId) }
                }

                AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    val owner = msg.data.getString("context_owner").orEmpty()
                    val replyTo = msg.replyTo
                    dispatchResultIo {
                        val target = AgentRuntimeResultStore.readOwned(this@AgentRuntimeService, runId, owner)
                        sendResultNow(replyTo, target?.result ?: AgentRuntimeWire.RunResult(
                            runId, false, "", "完整运行结果不可用", contextSnapshotRef = runId,
                        ))
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo, msg.data?.getBoolean("complete_result_refs") == true)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
    ) {
        val generation = ++startRequestGeneration
        val pending = PendingStartRequest(generation, incoming, replyTo)
        pendingStartRequests[incoming.request.runId] = pending
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                val request = AgentRuntimeImageTransfer.materialize(incoming)
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        RuntimeConfigRepository.currentRuntimeConfig()
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                if (pendingStartRequests[incoming.request.runId] !== pending) return@post
                pendingStartRequests.remove(incoming.request.runId)
                sendRequestIngestedTo(replyTo, incoming.request.runId)
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        startRun(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                            replyTo,
                        )
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure(
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime 无法读取图片"
                                is RuntimeConfigUnavailableException ->
                                    "请先在 Eta 中配置可用的模型"
                                else -> "Agent Runtime 无法准备请求"
                            },
                            replyTo,
                        )
                    },
                )
            }
        }
    }

    private data class QueuedRun(
        val request: AgentRuntimeWire.RunRequest,
        val replyTo: Messenger?,
        val queuedAt: Long,
    )

    private val queuedRuns = java.util.ArrayDeque<QueuedRun>()

    private val memoryQueueDrain = object : Runnable {
        override fun run() {
            drainQueuedRuns()
            if (queuedRuns.isNotEmpty()) mainHandler.postDelayed(this, MEMORY_QUEUE_INTERVAL_MS)
        }
    }

    /** 内存回落后放行排队会话：只放到并发上限，且不越过 95% 闸门。 */
    private fun drainQueuedRuns() {
        val now = System.currentTimeMillis()
        while (queuedRuns.isNotEmpty()) {
            val head = queuedRuns.peekFirst()
            if (now - head.queuedAt > MEMORY_QUEUE_TTL_MS) {
                queuedRuns.pollFirst()
                io.github.mangi.eta.core.EtaTrace.log(
                    "run.queue.timeout",
                    "runId=${head.request.runId} 排队超时已放弃",
                )
                sendResultTo(
                    head.replyTo,
                    AgentRuntimeWire.RunResult(
                        runId = head.request.runId,
                        ok = false,
                        content = "",
                        error = "内存长期紧张，这条会话排队超时已放弃。",
                    ),
                )
                continue
            }
            val memoryTier = AgentMemoryGate.tier(this)
            // 95 档只限流：有空位就放行；96 档起连排队也停止放行，只保留正在跑的任务。
            if (memoryTier >= AgentMemoryGate.TIER_LOCKDOWN) {
                io.github.mangi.eta.core.EtaTrace.log(
                    "run.queue.hold",
                    "${AgentMemoryGate.describe(this)} 继续等待",
                )
                break
            }
            if (runningSessionCount() >= AgentMemoryGate.sessionLimit()) break
            queuedRuns.pollFirst()
            io.github.mangi.eta.core.EtaTrace.log("run.queue.release", "runId=${head.request.runId}")
            startRun(head.request, head.replyTo)
        }
    }

    private fun enqueueRun(request: AgentRuntimeWire.RunRequest, replyTo: Messenger?): Boolean {
        if (queuedRuns.size >= MEMORY_QUEUE_MAX) return false
        queuedRuns.addLast(QueuedRun(request, replyTo, System.currentTimeMillis()))
        io.github.mangi.eta.core.EtaTrace.log(
            "run.queue",
            "runId=${request.runId} 入队/位置=${queuedRuns.size} " +
                AgentMemoryGate.describe(this),
        )
        mainHandler.removeCallbacks(memoryQueueDrain)
        mainHandler.postDelayed(memoryQueueDrain, MEMORY_QUEUE_INTERVAL_MS)
        return true
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        val automated = io.github.mangi.eta.agent.automation.AgentTaskScheduler.isRegistered(request.runId)
        // 自动任务已经放开：手动任务在跑时不再拒绝它，只受总并发上限约束。
        val reviewGeneration = AgentExperienceReview.foregroundStarted()
        val memoryMb = AgentMemoryGate.availableMb(this)
        val memoryTier = AgentMemoryGate.tier(this)
        if (memoryTier >= AgentMemoryGate.TIER_LOCKDOWN) {
            val frozen = memoryTier == AgentMemoryGate.TIER_FREEZE
            // 97%：冻结 Agent，先把在途会话的缓冲增量强制落盘，再停止接收新请求。
            val sealed = if (frozen) AgentRunCheckpointRecorder.sealAllActive() else 0
            io.github.mangi.eta.core.EtaTrace.log(
                "run.gate",
                "runId=${request.runId} 拒收 ${AgentMemoryGate.describe(this)} 存档=$sealed",
            )
            sendResultTo(
                replyTo,
                AgentRuntimeWire.RunResult(
                    runId = request.runId,
                    ok = false,
                    content = "",
                    error = if (frozen) {
                        "剩余内存仅 ${memoryMb}MB（已达冻结线），Eta 已冻结并保存全部会话现场；" +
                            "释放内存后再试。"
                    } else {
                        "剩余内存仅 ${memoryMb}MB，Eta 暂停接收新指令，仅保留正在执行的任务。"
                    },
                ),
            )
            return
        }
        if (memoryTier == AgentMemoryGate.TIER_QUEUE) {
            if (enqueueRun(request, replyTo)) return
            io.github.mangi.eta.core.EtaTrace.log(
                "run.gate",
                "runId=${request.runId} 排队已满 ${queuedRuns.size}/$MEMORY_QUEUE_MAX",
            )
            sendResultTo(
                replyTo,
                AgentRuntimeWire.RunResult(
                    runId = request.runId,
                    ok = false,
                    content = "",
                    error = "内存紧张（剩余 ${memoryMb}MB），排队也满了，请稍后再试。",
                ),
            )
            return
        }
        // 顺序固定：先内存三档闸门，再弹性会话上限。上限在这两个节点才重算。
        val sessionLimit = AgentMemoryGate.sessionLimit().coerceAtMost(MAX_CONCURRENT_RUNS)
        if (runningSessionCount() >= sessionLimit) {
            if (enqueueRun(request, replyTo)) return
            io.github.mangi.eta.core.EtaTrace.log(
                "run.gate",
                "runId=${request.runId} 并发排队已满 ${queuedRuns.size}/$MEMORY_QUEUE_MAX " +
                    AgentMemoryGate.describe(this),
            )
            sendResultTo(
                replyTo,
                AgentRuntimeWire.RunResult(
                    runId = request.runId,
                    ok = false,
                    content = "",
                    error = "当前剩余内存 ${memoryMb}MB，最多同时 ${sessionLimit} 个会话，" +
                        "排队也满了，请稍后再试。",
                ),
            )
            return
        }
        val session = AgentRuntimeSession(
            runId = request.runId,
            operation = request.operation,
            eventSink = { event ->
                val current = sessionFor(request.runId)
                if (current != null && event is AgentEvent.ToolStarted &&
                    (event.name in VirtualScreenRoutingPolicy.uiTools || event.name == "virtual_screen") &&
                    runBlocking { SettingsDataStore.settings() }.virtualScreenEnabled
                ) {
                    current.virtualUiRouted.set(true)
                }
                val virtualScreen = current?.let { usesVirtualUi(it) } == true
                RunProgressNotifier.note(event)
                islandBridge.update(request.runId, event, virtualScreen)
                sendEventTo(replyTo, event, virtualScreen)
            },
            resultSink = { result ->
                islandBridge.finish(result)
                sendResultTo(replyTo, result)
            },
        )
        session.virtualUiRouted.set(VirtualScreenSession.isOwnedBy(request.virtualScreenOwner))
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted || automated
        val executionHeld = AgentExecutionService.acquire(
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
        ) { session.controller.cancel() }
        if (!executionHeld && !allowBoundFallback) {
            session.complete(AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "",
                error = "无法启动后台执行服务，请返回 Eta 后重试",
            )) {}
            return
        }
        sessions[request.runId] = session
        RunProgressNotifier.start(this)
        RunProgressNotifier.noteSessionLimit(sessionLimit, MAX_CONCURRENT_RUNS)
        if (sessionLimit < MAX_CONCURRENT_RUNS) {
            // 上限被内存压低时留一条：事后能看出「为什么只能同时跑这么少」。
            io.github.mangi.eta.core.EtaTrace.log(
                "run.gate",
                "内存闸门 剩余=${AgentMemoryGate.availableMb()}MB 并发上限=$sessionLimit/" +
                    "$MAX_CONCURRENT_RUNS",
            )
        }
        io.github.mangi.eta.core.EtaTrace.log(
            "run.start",
            "runId=${request.runId} 并发=${runningSessionCount()}/$sessionLimit " +
                " operation=${request.operation} 自动=${automated}",
        )
        val handoffPayload = request.handoff?.payload?.let { raw -> AgentUiHandoffPayload.from(raw) }
        runningSessionInfo[request.runId] = RunningSessionInfo(
            runId = request.runId,
            conversationId = handoffPayload?.conversationId.orEmpty(),
            title = handoffPayload?.title.orEmpty(),
        )
        // 任何会话只要开始跑就记进「最近打开」：不再只认从胶囊点开过的会话。
        handoffPayload?.conversationId?.takeIf { it.isNotBlank() }?.let { conversationId ->
            runningSessionInfo[request.runId]?.let { info -> recentSessionInfo[request.runId] = info }
            noteRecentOpened(conversationId)
        }
        syncRunningSessions()
        islandBridge.start(request)
        lastCompletedRunContext = null
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        mainHandler.removeCallbacksAndMessages(hideToken)
        if (usesVirtualUi(session, runBlocking { SettingsDataStore.settings() }.virtualScreenEnabled)) {
            if (!capsulePinned) removeOverlayWindows()
        }
        state.value = AgentOverlayState.Initial
        syncRunningSessions()
        capsuleExpanded.value = false
        setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
        hasExecutedForegroundTool = false
        resultConversationId = request.handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            ?.takeIf(String::isNotBlank)
        synchronized(supplementsLock) {
            activeSupplements.clear()
            nextSupplementIndex = 1
            if (request.handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                val payload = AgentUiHandoffPayload.from(request.handoff.payload)
                activeSupplements += payload.supplements
                nextSupplementIndex = payload.lastSupplementIndex + 1
            }
        }

        thread(name = "agent-runtime") {
            try {
                executeRun(session, request, reviewGeneration)
            } finally {
                AgentExecutionService.updateRunStatus(this, null)
                AgentExecutionService.release("run:${request.runId}")
            }
        }
    }

    private fun executeRun(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
        reviewGeneration: Long,
    ) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            reviewGeneration = reviewGeneration,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = { it.withActiveSupplements() },
            onAcceptedEvent = { event, entrySurfaceGuard ->
                handleAcceptedRunEvent(session, event, entrySurfaceGuard)
            },
            persistArtifacts = ::persistRunArtifacts,
        ).execute(session, request)
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            session = session,
            result = outcome.result,
            entrySurfaceGuard = outcome.entrySurfaceGuard,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    CompletedRunContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        entrySurfaceGuard: EntrySurfaceGuard?,
    ) {
        if (sessionFor(session.runId) !== session) return
        val revealsOperation = AgentOverlayVisibilityPolicy.shouldRevealFor(event)
        val dismissesEntrySurface = AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)
        val virtualScreenEnabled = (revealsOperation || dismissesEntrySurface || event is AgentEvent.ToolStarted) &&
            runBlocking { SettingsDataStore.settings() }.virtualScreenEnabled
        val virtualUi = usesVirtualUi(session, virtualScreenEnabled)
        val revealsForegroundOperation = !virtualUi && revealsOperation
        val requiresEntrySurfaceDismissal = !virtualUi && dismissesEntrySurface
        val entrySurfaceReady = if (requiresEntrySurfaceDismissal && entrySurfaceGuard != null) {
            runCatching { entrySurfaceGuard.dismissOnce() }.getOrDefault(false)
        } else {
            true
        }
        mainHandler.post {
            if (sessionFor(session.runId) !== session) return@post
            if (
                !virtualUi && AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                    event,
                    entrySurfaceReady,
                )
            ) {
                hasExecutedForegroundTool = true
            }
            if (session.isTerminal) return@post
            runCatching {
                state.value = state.value.applyEvent(event)
                syncRunningSessions()
                if (revealsOperation && entrySurfaceReady) {
                    if (capsuleView == null) {
                        AgentHapticFeedback.perform(
                            this,
                            AgentHapticFeedback.Type.RUN_STARTED,
                        )
                    }
                    ensureOverlayVisible()
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        session: AgentRuntimeSession,
        result: AgentRuntimeWire.RunResult,
        entrySurfaceGuard: EntrySurfaceGuard?,
        completedContext: CompletedRunContext? = null,
    ) {
        mainHandler.post {
            // 收尾与「撤进度通知」必须无条件执行：会话被替换或重复收尾时若直接 return，
            // 通知会一直挂在通知栏（实测 start 12 次只 stop 1 次）。
            val needsOverlay = sessionFor(session.runId) === session
            lastCompletedRunContext = completedContext
            rememberFinishedSession(session.runId)
            sessions.remove(session.runId)
            if (sessions.isEmpty()) RunProgressNotifier.stop(this)
            if (!needsOverlay) return@post
            io.github.mangi.eta.core.EtaTrace.log(
                "run.end",
                "runId=${session.runId} 剩余并发=${runningSessionCount()}/" +
                    "${AgentMemoryGate.sessionLimit()}",
            )
            syncRunningSessions()
            if (session.virtualUiRouted.get()) {
                VirtualScreenNotification.showCompletion(
                    this,
                    result.ok,
                    if (result.ok) result.content.trim() else result.error.orEmpty(),
                )
            }
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "已停止") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = result.error.orEmpty(),
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(
        target: Messenger?,
        event: AgentEvent,
        virtualScreen: Boolean? = null,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event, virtualScreen)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun dispatchResultIo(block: () -> Unit) {
        try {
            resultIo.execute {
                try { block() } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("runtime_result_io_failed") {
                        "Agent runtime result I/O failed: type=${failure.safeLogType()}"
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            AndroidAgentLogger.info("Agent runtime result delivery deferred after service stop")
        }
    }

    private fun sendResultTo(target: Messenger?, result: AgentRuntimeWire.RunResult) {
        dispatchResultIo { sendResultNow(target, result) }
    }

    private fun sendResultNow(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, cacheDir)
            AgentWireText.send(target, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
            // 不把传输失败伪装成已交付终态；引用使新客户端保留 outbox，等待完整恢复。
            val fallback = AgentRuntimeWire.RunResult(result.runId, false, "",
                "完整结果传输失败，已保存的历史未删除。请重新打开会话恢复。",
                contextSnapshotRef = result.runId, operation = result.operation)
            try {
                target?.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                    data = AgentRuntimeWire.toBundle(fallback)
                })
            } catch (deliveryFailure: Exception) {
                AndroidAgentLogger.warnThrottled("runtime_result_failure_notice_undelivered") {
                    "Agent runtime result notice undelivered: type=${deliveryFailure.safeLogType()}"
                }
            }
        }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?, referencesOnly: Boolean) {
        dispatchResultIo { sendDrainedResultsNow(replyTo, referencesOnly) }
    }

    private fun sendDrainedResultsNow(replyTo: Messenger?, referencesOnly: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                if (referencesOnly) AgentRuntimeResultStore.pendingPage(this) else AgentRuntimeResultStore.list(this).take(8)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            // 多开：把所有在跑的会话都报给 UI。以前只报第一个，其余会被判成"中断"并清掉现场。
            val activeRunIds = sessions.values
                .filter { session -> !session.isTerminal }
                .map { session -> session.runId }
            msg.data = AgentRuntimeWire.activeRunBundle(activeRunIds)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?) {
        val session = sessionFor(runId)
        val attached = replyTo != null &&
            runId.isNotBlank() &&
            session?.runId == runId &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attached) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
        if (sessions.isNotEmpty()) return
        enterFinalState(
            AgentOverlayState(
                phase = AgentOverlayPhase.FAILED,
                status = AgentOverlayStatus.RunFailed,
                detailText = message
            )
        )
    }

    private fun requestStop() {
        val session = sessions.values.firstOrNull { it -> !it.isTerminal }
        if (session == null) {
            dismissAndStop()
            return
        }
        cancelRun(session.runId)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        io.github.mangi.eta.core.EtaTrace.log("run.cancel", "runId=$runId")
        pendingStartRequests.remove(runId)?.let { pending ->
            pending.incoming.close()
            sendRequestIngestedTo(pending.replyTo, runId)
            sendResultTo(
                pending.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = "已停止",
                ),
            )
            return
        }
        val session = sessionFor(runId) ?: return
        if (runId != session.runId) {
            AndroidAgentLogger.debug { "Agent runtime ignored stale cancel request" }
            return
        }
        if (!session.isTerminal) {
            session.controller.cancel()
            state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
        }
    }

    private fun requestPause() {
        sessions.values.forEach { session -> session.controller.pause() }
        state.value = state.value.copy(
            phase = AgentOverlayPhase.PAUSED,
            status = AgentOverlayStatus.Paused,
        )
    }

    private fun requestResume() {
        sessions.values.forEach { session -> session.controller.resume() }
        state.value = state.value.copy(
            phase = AgentOverlayPhase.RUNNING,
            status = AgentOverlayStatus.Continuing,
        )
    }

    /** 某个 run 属于哪个会话；插话靠它做精确匹配。 */
    private fun conversationIdOfRun(runId: String): String =
        runningSessionInfo[runId]?.conversationId.orEmpty()

    /** 「上一次跑完的运行」属于哪个会话；续跑前也要核对，否则会拿别的会话的上下文续跑。 */
    private fun conversationIdOfCompletedRun(completed: CompletedRunContext?): String =
        completed?.request?.handoff
            ?.takeIf { handoff -> handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { handoff -> AgentUiHandoffPayload.from(handoff.payload).conversationId }
            .orEmpty()

    private fun requestSupplement(text: String, conversationId: String = "") {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        setCapsuleInputMode(focusable = false)
        val activeSessions = sessions.values.filter { session -> !session.isTerminal }
        val target = if (conversationId.isNotBlank()) {
            // 只投给同会话的 run；以前取「最后一个未结束的 run」，本机在跑长任务时
            // 别的会话发的内容会被吸进来（串回复），所以必须有会话标识才算匹配。
            activeSessions.lastOrNull { session -> conversationIdOfRun(session.runId) == conversationId }
        } else {
            // 调用方没给会话信息（旧客户端）：只有唯一候选时才敢投，避免误伤别的会话。
            activeSessions.singleOrNull()
        }
        if (target != null) {
            val event = target.steer(supplementText) {
                recordSupplementEvent(supplementText)
            }
            if (event != null) {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                state.value = state.value.applyEvent(event)
                return
            }
            if (!target.isTerminal) {
                state.value = state.value.copy(
                    status = AgentOverlayStatus.Finishing,
                )
                return
            }
        }

        val completed = lastCompletedRunContext
        if (conversationId.isNotBlank() &&
            conversationIdOfCompletedRun(completed) != conversationId
        ) {
            io.github.mangi.eta.core.EtaTrace.log(
                "run.steer.reject",
                "插话无同会话运行 会话=$conversationId 在跑=${activeSessions.size}",
            )
            return
        }
        if (completed == null) return
        if (completed.request.operation != AgentRuntimeWire.OP_CHAT ||
            completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
        )
        startRun(continuationRequest)
    }

    private fun recordSupplementEvent(text: String): AgentEvent.UserSupplementReceived {
        val supplement = synchronized(supplementsLock) {
            AgentUiHandoffPayload.Supplement(
                index = nextSupplementIndex++,
                text = text,
                createdAt = System.currentTimeMillis(),
            ).also { activeSupplements += it }
        }
        return AgentEvent.UserSupplementReceived(
            index = supplement.index,
            text = supplement.text,
        )
    }

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun usesVirtualUi(session: AgentRuntimeSession, virtualScreenEnabled: Boolean = false): Boolean =
        (session.virtualUiRouted.get() || virtualScreenEnabled) && !session.mainScreenFallbackApproved.get()

    private fun showOverlay() {
        if (capsuleView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY 免 SYSTEM_ALERT_WINDOW 权限；仅回退态（无障碍未启用）才需检查
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // 屏幕边缘流光常显已关闭：任务进行中不再画氛围光，避免一直干扰视线。

        // ── 状态胶囊窗口：状态栏下方居中，收起时一句状态，点击展开控制 ─────
        val capsule = createOverlayComposeView {
            AgentOverlayCapsule(
                state = state.value,
                expanded = capsuleExpanded.value,
                onToggleExpanded = ::toggleCapsule,
                onCollapsedSettled = ::onCapsuleCollapsedSettled,
                onPause = ::requestPause,
                onResume = ::requestResume,
                onStop = ::requestStop,
                onSupplementModeChange = ::setCapsuleInputMode,
                onSupplement = { supplementText ->
                    requestSupplement(
                        supplementText,
                        state.value.runningSessions.firstOrNull()?.conversationId.orEmpty(),
                    )
                },
                onOpenConversation = ::openConversation,
                onDragBy = ::moveCapsuleBy,
                onDragFinished = ::snapCapsuleToEdge,
                onCollapseRequest = ::collapseCapsule,
                recentOpened = state.value.recentOpenedSessions,
                unreadSessions = state.value.unreadSessions,
                collapsedX = capsuleOffsetX.value,
                collapsedY = capsuleOffsetY.value,
                fullScreen = capsuleFullScreen.value,
            )
        }
        val savedOverlay = runBlocking { SettingsDataStore.settings() }
        savedCapsuleX = savedOverlay.overlayCapsulePosX
        savedCapsuleY = savedOverlay.overlayCapsulePosY
        val capsuleLp = capsuleLayoutParams()
        runCatching { wm.addView(capsule, capsuleLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_add_view_failed") {
                "Agent runtime capsule addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        capsuleView = capsule
        capsuleParams = capsuleLp
        capsuleCollapsedX = capsuleLp.x
        capsuleCollapsedY = capsuleLp.y
        capsuleOffsetX.value = capsuleLp.x
        capsuleOffsetY.value = capsuleLp.y
    }

    private fun toggleCapsule() {
        val expand = !capsuleExpanded.value
        // 先把窗口扩到展开档再开始动画；收起由胶囊在动画结束后回调 onCollapsedSettled 缩窗。
        if (expand) {
            capsuleParams?.let {
                capsuleCollapsedX = it.x
                capsuleCollapsedY = it.y
            }
            // 展开态用全屏透明窗口：面板以外点任意位置都能收起。
            capsuleFullScreen.value = true
            setCapsuleWindowFullScreen()
        }
        capsuleExpanded.value = expand
    }

    /** 展开态改成全屏透明窗口：面板外点一下就能收起，面板本体仍然只占一小块。 */
    private fun setCapsuleWindowFullScreen() {
        val lp = capsuleParams ?: return
        val capsule = capsuleView ?: return
        val wm = windowManager ?: return
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.x = 0
        lp.y = 0
        runCatching { wm.updateViewLayout(capsule, lp) }
    }

    /** 点面板外任意位置：收起大面板（收起动画结束后由 onCollapsedSettled 缩窗复位）。 */
    private fun collapseCapsule() {
        if (!capsuleExpanded.value) return
        capsuleExpanded.value = false
    }

    /** 收起后回到用户拖到的位置（贴边就继续贴边）。 */
    private fun restoreCapsuleCollapsedPosition() {
        val lp = capsuleParams ?: return
        val capsule = capsuleView ?: return
        val wm = windowManager ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val edge = dpToPx(CAPSULE_EDGE_MARGIN_DP)
        if (capsuleCollapsedX != Int.MIN_VALUE) {
            lp.x = capsuleCollapsedX.coerceIn(edge, maxOf(edge, screenWidth - lp.width - edge))
        }
        if (capsuleCollapsedY != Int.MIN_VALUE) {
            lp.y = capsuleCollapsedY.coerceIn(edge, maxOf(edge, screenHeight - lp.height - edge))
        }
        runCatching { wm.updateViewLayout(capsule, lp) }
    }

    private fun setCapsuleWindowWidth(widthDp: Int) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val width = dpToPx(widthDp)
        if (lp.width == width) return
        lp.width = width
        val edge = dpToPx(CAPSULE_EDGE_MARGIN_DP)
        lp.x = lp.x.coerceIn(edge, maxOf(edge, resources.displayMetrics.widthPixels - width - edge))
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_resize_width_failed") {
                "Agent runtime capsule resize failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /** 收起态拖动小胶囊：跟着手指走，超出屏幕会被夹住。 */
    private fun moveCapsuleBy(dx: Float, dy: Float) {
        val lp = capsuleParams ?: return
        val capsule = capsuleView ?: return
        val wm = windowManager ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val edge = dpToPx(CAPSULE_EDGE_MARGIN_DP)
        lp.x = (lp.x + dx.toInt()).coerceIn(edge, maxOf(edge, screenWidth - lp.width - edge))
        lp.y = (lp.y + dy.toInt()).coerceIn(edge, maxOf(edge, screenHeight - lp.height - edge))
        capsuleCollapsedX = lp.x
        capsuleCollapsedY = lp.y
        capsuleOffsetX.value = lp.x
        capsuleOffsetY.value = lp.y
        runCatching { wm.updateViewLayout(capsule, lp) }
    }

    /** 松手贴边：只有靠近左右边缘（屏宽 15% 内）才吸附，放中间就停在中间。 */
    private fun snapCapsuleToEdge() {
        val lp = capsuleParams ?: return
        val capsule = capsuleView ?: return
        val wm = windowManager ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val edge = dpToPx(CAPSULE_EDGE_MARGIN_DP)
        val edgeZone = (screenWidth * 0.15f).toInt()
        val maxX = maxOf(edge, screenWidth - lp.width - edge)
        val targetX = when {
            lp.x <= edgeZone -> edge
            lp.x >= maxX - edgeZone -> maxX
            else -> lp.x.coerceIn(edge, maxX)
        }
        if (lp.x != targetX) {
            lp.x = targetX
            runCatching { wm.updateViewLayout(capsule, lp) }
        }
        capsuleCollapsedX = lp.x
        capsuleCollapsedY = lp.y
        capsuleOffsetX.value = lp.x
        capsuleOffsetY.value = lp.y
        persistCapsulePosition(lp.x, lp.y)
    }

    /** 记住位置：重启 App 后小胶囊还停在上次的地方。 */
    private fun persistCapsulePosition(x: Int, y: Int) {
        savedCapsuleX = x
        savedCapsuleY = y
        runCatching {
            runBlocking {
                SettingsDataStore.updateSettings { settings ->
                    settings.copy(overlayCapsulePosX = x, overlayCapsulePosY = y)
                }
            }
        }
    }

    /** 展开态窗口高度：有运行中会话时按条数加高，保证每条会话都能完整显示、点得到。 */
    private fun capsuleExpandedHeightDp(): Int {
        val count = state.value.runningSessions.size
        return if (count == 0) {
            CAPSULE_CONTROLS_HEIGHT_DP
        } else {
            (CAPSULE_SESSIONS_BASE_HEIGHT_DP + count * CAPSULE_SESSION_ROW_HEIGHT_DP)
                .coerceAtMost(CAPSULE_SESSIONS_MAX_HEIGHT_DP)
        }
    }

    /** 记一笔"最近打开过的对话"：从胶囊点开的会话都会进来。 */
    private fun noteRecentOpened(conversationId: String) {
        if (conversationId.isBlank()) return
        recentOpenedIds.remove(conversationId)
        recentOpenedIds.addFirst(conversationId)
        while (recentOpenedIds.size > RECENT_OPENED_KEEP) recentOpenedIds.removeLast()
        recentOpenedState.value = recentOpenedIds.map { id ->
            val title = recentSessionInfo.entries
                .firstOrNull { it.value.conversationId == id }
                ?.value
                ?.title
                ?.takeIf { it.isNotBlank() }
            AgentOverlayRunningSession(
                runId = id,
                conversationId = id,
                title = title ?: "会话",
                running = false,
            )
        }
    }

    private fun onCapsuleCollapsedSettled() {
        if (!capsuleExpanded.value) {
            setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
            setCapsuleWindowWidth(CAPSULE_COLLAPSED_WIDTH_DP)
            restoreCapsuleCollapsedPosition()
            // 缩窗到位后再解除全屏标记：动画全程都按小胶囊的真实位置摆放面板。
            capsuleOffsetX.value = capsuleCollapsedX.takeIf { it != Int.MIN_VALUE } ?: capsuleOffsetX.value
            capsuleOffsetY.value = capsuleCollapsedY.takeIf { it != Int.MIN_VALUE } ?: capsuleOffsetY.value
            capsuleFullScreen.value = false
        }
    }

    private fun setCapsuleWindowHeight(heightDp: Int) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val height = dpToPx(heightDp)
        if (lp.height == height) return
        lp.height = height
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_resize_failed") {
                "Agent runtime capsule resize failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun showResultCard(wm: WindowManager) {
        if (resultCardView != null) return
        val card = createOverlayComposeView {
            AgentResultCard(
                state = state.value,
                onClose = ::dismissAndStop,
                onOpenEta = ::openEtaFromResult,
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
        registerResultCardBack(card)
        card.requestFocus()
    }

    /**
     * 结果卡片是任务结束后的停留界面，系统返回应先关闭它，而不是退回底下的应用。
     * 窗口需可获焦才能收到返回；卡片外仍是 FLAG_NOT_TOUCH_MODAL，触摸照常穿透到下层。
     */
    /**
     * 回到 Eta 本体并定位到本次任务的会话。卡片窗口可见时属于前台可见的用户操作，
     * 允许从服务启动 Activity；启动失败时保留卡片，不让用户丢失结果。
     */
    private fun openEtaFromResult() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                resultConversationId?.let { id ->
                    action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                    putExtra(MainActivity.EXTRA_CONVERSATION_ID, id)
                }
            }
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pendingIntentBackgroundActivityStartMode = if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                } else {
                    @Suppress("DEPRECATION")
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
            }
        }
        val launched = runCatching {
            PendingIntent.getActivity(
                this, RESULT_OPEN_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send(this, 0, null, null, null, null, options.toBundle())
        }
        if (launched.isFailure) {
            AndroidAgentLogger.warn("Agent runtime result open failed: type=${launched.exceptionOrNull()?.safeLogType()}")
            return
        }
        dismissAndStop()
    }

    private fun registerResultCardBack(view: View) {
        unregisterResultCardBack()
        val dispatcher = view.findOnBackInvokedDispatcher() ?: run {
            AndroidAgentLogger.warn("Agent runtime result card back dispatcher unavailable")
            return
        }
        val callback = OnBackInvokedCallback(::dismissAndStop)
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        resultCardBack = dispatcher to callback
    }

    private fun unregisterResultCardBack() {
        val (dispatcher, callback) = resultCardBack ?: return
        resultCardBack = null
        runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    // 部分 ROM 会给 TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas；Miuix 的
                    // RuntimeShader 只检查系统版本，因此系统浮层统一使用其普通圆角回退。
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        content()
                    }
                }
            }
        }

    /**
     * 胶囊窗口使用固定宽度与分档高度，动画只在窗口内进行。
     * WRAP_CONTENT 会让每一帧的尺寸变化都触发 WindowManager 重新布局整个窗口，这是卡顿的来源；
     * 但窗口内透明区域同样会吞掉触摸（包括 Agent 自己注入的点击），所以高度只在需要时扩大：
     * 展开前先扩到目标档，收起动画结束后再缩回，见 [setCapsuleWindowHeight]。
     */
    private fun capsuleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dpToPx(CAPSULE_COLLAPSED_WIDTH_DP),
            dpToPx(CAPSULE_COLLAPSED_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 优先回到上次拖到的位置；没拖过就贴在状态栏下方、水平居中。
            gravity = Gravity.TOP or Gravity.START
            val screenWidth = resources.displayMetrics.widthPixels
            x = if (savedCapsuleX >= 0) {
                savedCapsuleX.coerceIn(
                    dpToPx(CAPSULE_EDGE_MARGIN_DP),
                    maxOf(dpToPx(CAPSULE_EDGE_MARGIN_DP), screenWidth - dpToPx(CAPSULE_COLLAPSED_WIDTH_DP) - dpToPx(CAPSULE_EDGE_MARGIN_DP)),
                )
            } else {
                ((screenWidth - dpToPx(CAPSULE_COLLAPSED_WIDTH_DP)) / 2).coerceAtLeast(0)
            }
            y = if (savedCapsuleY >= 0) savedCapsuleY else statusBarHeightPx()
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    private fun statusBarHeightPx(): Int {
        val metrics = (overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.currentWindowMetrics ?: return dpToPx(28)
        return metrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())
            .top
            .takeIf { it > 0 } ?: dpToPx(28)
    }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 半屏底部居中，窗口外触摸穿透
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（免 SYSTEM_ALERT_WINDOW 权限，且截图
        // filterValidWindows 可过滤）；需用无障碍服务 context 创建，否则 BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    @Suppress("DEPRECATION")
    private fun glowLayoutParams(): WindowManager.LayoutParams {
        // 真实屏幕高度（含状态栏 + 导航栏），MATCH_PARENT 在部分设备不含系统栏
        val realHeight = runCatching {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
            point.y
        }.getOrDefault(WindowManager.LayoutParams.MATCH_PARENT)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            realHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 全屏覆盖（含状态栏/导航栏），触摸穿透不拦截页面操作；
            // TYPE_ACCESSIBILITY_OVERLAY 让 takeScreenshotOfWindow 过滤掉，对 Agent 透明
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 非应用窗口默认按系统栏与挖孔收缩窗口框，光带画出的圆角就会整体下移，和物理屏幕角错位。
            // 显式不避让任何 inset，窗口原点才与屏幕原点重合，Display 上报的圆角半径才对得上。
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /**
     * 屏幕四角的物理圆角半径。光效窗口覆盖整屏，直接取 Display 上报的值；
     * 部分 ROM 不上报时用 0，兜底按常见大圆角机型估一个保守值，宁可略小也不让光带越出屏幕。
     */
    private fun screenCornerRadii(): ScreenCornerRadii {
        val display = (overlayContext().getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val fallback = dpToPx(FALLBACK_SCREEN_CORNER_DP).toFloat()
        fun radius(position: Int): Float =
            display?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }?.toFloat() ?: fallback
        return ScreenCornerRadii(
            topLeft = radius(android.view.RoundedCorner.POSITION_TOP_LEFT),
            topRight = radius(android.view.RoundedCorner.POSITION_TOP_RIGHT),
            bottomRight = radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT),
            bottomLeft = radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }

    private fun setCapsuleInputMode(focusable: Boolean) {
        setCapsuleWindowHeight(
            if (focusable) CAPSULE_SUPPLEMENT_HEIGHT_DP
            else if (capsuleExpanded.value) capsuleExpandedHeightDp()
            else CAPSULE_COLLAPSED_HEIGHT_DP,
        )
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_focus_update_failed") {
                "Agent runtime capsule focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState, keepVisible: Boolean = false) {
        state.value = finalState
        // 只要有一个会话输出完成/失败，胶囊就留一条提示，直到用户打开 Eta 或点开会话。
        unreadFinished = true
        unreadFinishedCount = (unreadFinishedCount + 1).coerceAtMost(9)

        if (hasExecutedForegroundTool) {
            // 撤掉边缘光，改显半屏结果卡片，不自动关闭；胶囊保留并给出同样的完成提示。
            capsuleExpanded.value = false
            removeGlowWindow()
            mainHandler.removeCallbacksAndMessages(hideToken)
            setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
            state.value = finishedNoticeState(finalState).copy(finishedBadge = unreadFinishedCount)
            ensureOverlayVisible()
            windowManager?.let(::showResultCard)
        } else if (capsulePinned) {
            // 胶囊常驻：收起但保留「已完成」提示，不退回空闲，不退出服务。
            mainHandler.removeCallbacksAndMessages(hideToken)
            capsuleExpanded.value = false
            setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
            state.value = finishedNoticeState(finalState).copy(finishedBadge = unreadFinishedCount)
            ensureOverlayVisible()
        } else {
            // 完成提示不自动消失：保留胶囊显示「已完成」，等用户打开 Eta 再收。
            mainHandler.removeCallbacksAndMessages(hideToken)
            capsuleExpanded.value = false
            setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
            state.value = finishedNoticeState(finalState).copy(finishedBadge = unreadFinishedCount)
            ensureOverlayVisible()
        }
    }

    /** 完成/失败后胶囊里保留的一条提示文案。 */
    private fun finishedNoticeState(finalState: AgentOverlayState): AgentOverlayState {
        val failed = finalState.phase == io.github.mangi.eta.agent.overlay.AgentOverlayPhase.FAILED
        // 完成后保留会话列表：胶囊展开后仍能点回刚跑完的会话。
        return finalState.copy(
            thought = if (failed) "任务失败，点开查看" else "已完成，点开查看结果",
        )
    }

    private fun removeGlowWindow() {
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView = null
        glowParams = null
    }

    private fun removeAmbientWindows() {
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView = null
        glowView = null
        capsuleParams = null
        glowParams = null
    }

    private fun removeOverlayWindows() {
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        resultCardParams = null
        removeAmbientWindows()
        windowManager = null
    }

    private fun dismissAndStop() {
        unreadFinished = false
        if (runningSessionCount() > 0) {
            // 还有任务在跑：只收窗口，服务留着——并发时不能把别的会话一起带走。
            removeOverlayWindows()
            return
        }
        if (capsulePinned) {
            // 常驻模式下不撤胶囊，只收回到折叠态。
            capsuleExpanded.value = false
            setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
            state.value = AgentOverlayState(status = AgentOverlayStatus.Idle)
            ensureOverlayVisible()
            return
        }
        removeOverlayWindows()
        stopSelf()
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    private fun AgentRuntimeWire.RunRequest.withActiveSupplements(): AgentRuntimeWire.RunRequest {
        val handoff = handoff ?: return this
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return this
        val supplements = synchronized(supplementsLock) { activeSupplements.toList() }
        if (supplements.isEmpty()) return this
        val payload = AgentUiHandoffPayload.from(handoff.payload).copy(
            supplements = supplements,
        )
        return copy(
            handoff = handoff.copy(payload = payload.toJson())
        )
    }

    internal companion object {
        /** 同时运行的会话上限：模型推理并行，工具执行在工具层排队。 */
        /** 会话并发的硬上限；实际并发由 AgentMemoryGate.sessionLimit() 按剩余内存决定。 */
        const val MAX_CONCURRENT_RUNS = 6
        /** 排队最长保留时间；超时说明内存一直紧，直接放弃，不让会话永远挂着。 */
        private const val MEMORY_QUEUE_TTL_MS = 5 * 60 * 1000L
        /** 排队巡检间隔。 */
        private const val MEMORY_QUEUE_INTERVAL_MS = 5_000L
        /** 排队上限，避免内存紧张时请求无限堆积。 */
        private const val MEMORY_QUEUE_MAX = 8

        const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        /** 胶囊展开列表里最多保留几条会话（运行中 + 最近完成）。 */
        const val SESSION_LIST_LIMIT = 3

        const val ACTION_PIN_CAPSULE = "io.github.mangi.eta.agent.runtime.PIN_CAPSULE"
        const val ACTION_UNPIN_CAPSULE = "io.github.mangi.eta.agent.runtime.UNPIN_CAPSULE"
        const val ACTION_CLEAR_READ = "io.github.mangi.eta.agent.runtime.CLEAR_READ"
        const val HIDE_DELAY_MS = 2_500L
        const val RESULT_REVIEW_DELAY_MS = 120_000L
        const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        private const val RESULT_OPEN_REQUEST_CODE = 1108
        /** 收起态窗口宽度：小胶囊本体 64dp 加左右各 12dp 内边距。 */
        private const val CAPSULE_COLLAPSED_WIDTH_DP = 88

        /** 展开态窗口宽度：容纳 372dp 面板加左右内边距。 */
        private const val CAPSULE_EXPANDED_WIDTH_DP = 396
        /** 收起态是个真正的小胶囊，只留图标和角标。 */
        private const val CAPSULE_COLLAPSED_HEIGHT_DP = 60

        /** 最近打开过的对话：内部最多记 5 条，面板上最多显示 3 条（多的折叠）。 */
        /** 胶囊允许贴到屏幕边缘（0 = 不留白），展开面板的留白另有处理。 */
        private const val CAPSULE_EDGE_MARGIN_DP = 0
        /** 「最新回复」栏最多列几条。 */
        private const val UNREAD_SESSION_KEEP = 3
        private const val RECENT_OPENED_KEEP = 5
        private const val RECENT_OPENED_SHOWN = 3
        private const val CAPSULE_CONTROLS_HEIGHT_DP = 152
        /** 展开面板基础高度：顶栏 + 操作按钮。 */
        private const val CAPSULE_SESSIONS_BASE_HEIGHT_DP = 132
        /** 每条会话占的高度（紧凑，但整行都能点）。 */
        private const val CAPSULE_SESSION_ROW_HEIGHT_DP = 62
        /** 展开面板高度上限：约占屏幕 1/3，别把屏幕占满。 */
        private const val CAPSULE_SESSIONS_MAX_HEIGHT_DP = 332
        private const val CAPSULE_SUPPLEMENT_HEIGHT_DP = 320
        private const val FALLBACK_SCREEN_CORNER_DP = 28
        const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private data class CompletedRunContext(
        val request: AgentRuntimeWire.RunRequest,
        val response: AgentModelClient.ModelResponse.Text,
    )

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
