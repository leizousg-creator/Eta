package io.github.mangi.eta.agent.tool

import android.content.Context
import android.graphics.Rect
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceControlUnavailableException
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.media.ModelImageBudget
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.AgentPlanMode
import io.github.mangi.eta.agent.model.provider.AgentHttpClient
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.web.AgentWebTools
import io.github.mangi.eta.agent.web.WebHttpTransport
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.model.catalog.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.catalog.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.catalog.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.provider.AgentHttpClient
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillLoader
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.agent.skill.SkillResourceReader
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.linux.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.linux.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.agent.tool.chat.AgentImageTools
import io.github.mangi.eta.agent.tool.device.ActiveDisplayUnavailableException
import io.github.mangi.eta.agent.tool.device.AgentStructuredDeviceTools
import io.github.mangi.eta.agent.tool.device.AppLaunchTools
import io.github.mangi.eta.agent.tool.device.DeviceContextTool
import io.github.mangi.eta.agent.tool.device.GuiInteractionTools
import io.github.mangi.eta.agent.tool.device.InvalidToolArgumentException
import io.github.mangi.eta.agent.web.AgentWebTools
import io.github.mangi.eta.agent.web.WebHttpTransport
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.repository.AgentMemoryException
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.WebToolsSettingsRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject

internal class AgentLocalTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val browserRunId: String = "",
    private val browserToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS)
    },
    private val terminalToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)
    },
    private val deviceDirectToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS)
    },
    private val deviceSensitiveReadToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS)
    },
    private val deviceSensitiveActionToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS)
    },
    private val memoryToolsEnabled: () -> Boolean = {
        runBlocking { AgentMemoryRepository.isEnabled() }
    },
    private val memoryWritable: Boolean = true,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val screenObservationProvider: (
        (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation
    )? = null,
    private val beforeToolExecution: (String) -> ToolExecutionDecision = {
        ToolExecutionDecision.Allow
    },
    skillIndexService: SkillIndexService? = null,
    skillLoader: SkillLoader? = null,
    skillResourceReader: SkillResourceReader? = null,
    githubSkillSource: PublicGitHubSkillSource? = null,
    skillPackageInstaller: SkillPackageInstaller? = null,
    runAvailableSkillIds: Set<String> = emptySet(),
    pendingSkillConflict: PendingSkillConflictCapability? = null,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    /** 技能自写服务：供 skills_manage 使用；为空时该工具回报不可用。 */
    private val skillAuthoringService: io.github.mangi.eta.agent.skill.SkillAuthoringService? = null,
    private val virtualDisplayEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED)
    },
    private val isToolEnabled: (String) -> Boolean = {
        Prefs.isToolEnabled(it)
    },
    private val lsposedAvailable: () -> Boolean = { EtaApp.serviceInstance != null },
    private val guiControl: GuiControlCoordinator.RunControl? = null,
    private val imageBudgetProvider: () -> ModelImageBudget = { ModelImageBudget.DEFAULT },
    browserSession: AgentBrowserSession? = null,
) : AgentModelClient.ToolExecutor, AutoCloseable {

    private val closed = AtomicBoolean(false)

    @Volatile
    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY

    private val browserSessionRef = lazy { browserSession ?: AgentBrowserSession() }

    /**
     * 命名标签页。
     *
     * 复用 [AgentBrowserSession] 的一页一实例模型，不去重构 WebView 管理：
     * 每个标签名对应一个独立会话，工具通过 `tab` 参数寻址。空名字始终落到主会话，
     * 因此不带参数的调用行为与引入标签前完全一致。
     */
    private val browserTabs = java.util.concurrent.ConcurrentHashMap<String, AgentBrowserSession>()

    private val tabNamePattern = Regex("[A-Za-z0-9_-]{1,32}")
    private val deviceController = RootShellDeviceController(logger, screenshotExcludedPackages, rootAvailable)
    private val rootCommandExecutor = BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
    private val structuredDeviceTools = AgentStructuredDeviceTools(
        context = context,
        logger = logger,
        root = rootCommandExecutor,
        rootAvailable = rootAvailable,
        foregroundAction = ::executeForegroundIntent,
    )
    private val imageTools = AgentImageTools(context, rootCommandExecutor, rootAvailable, imageBudgetProvider)
    private val webTools = AgentWebTools(
        transport = WebHttpTransport(AgentHttpClient.client),
        localNetworkAccess = { LocalNetworkPermission.accessState(context).name.lowercase(Locale.ROOT) },
        runtimeConfig = { runBlocking { WebToolsSettingsRepository.resolveRuntimeConfig(context) } },
    )
    val terminalExecutionContext = LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment.let { environment ->
        TerminalExecutionContext(
            linuxEnvironment = environment,
            linuxRootfsPath = LinuxEnvironmentPaths.rootfsDir(context, checkNotNull(environment.linuxDistribution)).absolutePath,
            sharedMounts = SharedFolderMounts.current(),
        )
    }
    private val terminalController = RootShellTerminalController(
        logger = logger,
        rootAvailable = rootAvailable,
        executionContext = terminalExecutionContext,
        backgroundJobs = TerminalRuntime.backgroundJobs(context),
        detachedSupervisor = DetachedTaskSupervisor(
            logger = logger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
            linuxRootfsPath = terminalExecutionContext.linuxRootfsPath,
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
            rootAvailable = rootAvailable,
        ),
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val guiTools = GuiInteractionTools(
        context = context,
        logger = logger,
        deviceController = deviceController,
        screenObservationProvider = screenObservationProvider,
        requireContext = ::requireContext,
        activeDisplayId = { activeDisplayId },
        setActiveDisplayId = { activeDisplayId = it },
        isClosed = closed::get,
        checkCancellation = ::checkCancellation,
    )
    private val appLaunchTools = AppLaunchTools(
        logger = logger,
        requireContext = ::requireContext,
        rootAvailable = rootAvailable,
        virtualDisplayEnabled = virtualDisplayEnabled,
        lsposedAvailable = lsposedAvailable,
        activeDisplayId = { activeDisplayId },
        setActiveDisplayId = { activeDisplayId = it },
        checkCancellation = ::checkCancellation,
    )
    private val skillTools = AgentSkillTools(
        skillIndexService = skillIndexService,
        skillLoader = skillLoader,
        skillResourceReader = skillResourceReader,
        githubSkillSource = githubSkillSource,
        skillPackageInstaller = skillPackageInstaller,
        runAvailableSkillIds = runAvailableSkillIds,
        pendingSkillConflict = pendingSkillConflict,
        isClosed = closed::get,
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        guiTools.clearObservation()
        if (browserSessionRef.isInitialized()) browserSessionRef.value.interruptAgentAction(browserRunId)
        // 标签页会话由本类自行持有，不归 BrowserSessionRegistry 管，退役时要一并打断并清空。
        browserTabs.values.forEach { session ->
            runCatching { session.interruptAgentAction(browserRunId) }
        }
        browserTabs.clear()
        webTools.close()
        terminalController.interruptAll()
        deviceController.close()
        rootCommandExecutor.close()
        skillTools.close()
        guiControl?.close()
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (closed.get()) throw AgentRunCancelledException()
        val control = guiControl
        return if (control != null && AgentToolRequirements.requiresGuiControl(toolCall.name)) {
            control.withAction { generation -> executeLocal(toolCall, generation) }
        } else {
            executeLocal(toolCall, null)
        }
    }

    private fun executeLocal(
        toolCall: AgentModelClient.ToolCall,
        guiGeneration: Long?,
    ): AgentModelClient.ToolResult =
        runCatching {
            if (closed.get()) throw AgentRunCancelledException()
            val args = JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
            if (AgentToolRequirements.find(toolCall.name) != null &&
                AgentToolRequirements.rootDenied(toolCall.name, args, rootAvailable())
            ) {
                return@runCatching textResult(errorResult("ROOT_REQUIRED", "此操作需要 Root 授权，本次未执行"))
            }
            if (!isToolEnabled(toolCall.name)) {
                return@runCatching AgentModelClient.ToolResult(
                    content = errorResult("TOOL_DISABLED", "工具 ${toolCall.name} 已在工具列表中关闭，本次未执行"),
                    sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
                )
            }
            deviceToolPermissionError(toolCall.name)?.let { return@runCatching it }
            if (AgentPlanMode.enabled && !AgentPlanMode.allowsTool(toolCall.name)) {
                return@runCatching textResult(
                    // ETA-FORK: 计划模式在工具执行入口硬拦截写操作
                    errorResult("PLAN_MODE_READ_ONLY", AgentPlanMode.blockedMessage(toolCall.name)),
                )
            }
            memoryToolPermissionError(toolCall.name)?.let { return@runCatching it }
            if (guiGeneration != null && !guiTools.matchesGeneration(guiGeneration)) {
                guiTools.clearObservation()
                if (AgentToolRequirements.requiresFreshObservation(toolCall.name)) {
                    return@runCatching textResult(
                        errorResult(
                            "NO_OBSERVATION",
                            "手机操作权已变化，请先调用 observe_screen 重新观察，再操作当前页面",
                        ),
                    )
                }
            }
            val preparation = if (AgentToolRequirements.hasForegroundIntentFallback(toolCall.name)) {
                ToolExecutionDecision.Allow
            } else {
                beforeToolExecution(toolCall.name)
            }
            when (val decision = preparation) {
                ToolExecutionDecision.Allow -> Unit
                is ToolExecutionDecision.Reject -> {
                    if (decision.code.startsWith("ACCESSIBILITY_")) guiTools.clearObservation()
                    return@runCatching textResult(
                        errorResult(
                            code = decision.code,
                            message = decision.message,
                        ),
                    )
                }
            }
            when (toolCall.name) {
                "get_current_context" -> textResult(DeviceContextTool.current(context))
                "search_apps" -> textResult(appLaunchTools.searchApps(args))
                "launch_app" -> {
                    guiTools.clearObservation()
                    textResult(appLaunchTools.launchApp(args))
                }
                "present_to_user" -> {
                    guiTools.clearObservation()
                    textResult(appLaunchTools.presentToUser(args))
                }
                "open_uri" -> {
                    guiTools.clearObservation()
                    textResult(appLaunchTools.openUri(args))
                }
                "browser_use" -> browserUse(args, toolCall.id)
                "browser_devtools" -> browserDevtools(args, toolCall.id)
                "web_search", "fetch_url" -> {
                    if (!browserToolsEnabled()) textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页搜索、读取与浏览器工具"))
                    else textResult(webTools.execute(toolCall.name, args))
                }
                "observe_screen" -> guiTools.observeScreen(args, guiGeneration)
                "release_gui" -> {
                    guiTools.clearObservation()
                    guiControl?.release()
                    textResult(JSONObject().put("ok", true).put("tool", "release_gui").toString())
                }
                "tap" -> guiTools.afterAction(guiTools.tap(args))
                "tap_area" -> guiTools.afterAction(guiTools.tapArea(args))
                "tap_element" -> guiTools.afterAction(guiTools.tapElement(args))
                "long_press" -> guiTools.afterAction(guiTools.longPress(args))
                "long_press_element" -> guiTools.afterAction(guiTools.longPressElement(args))
                "swipe" -> guiTools.afterAction(guiTools.swipe(args))
                "scroll" -> guiTools.afterAction(guiTools.scroll(args))
                "scroll_element" -> guiTools.afterAction(guiTools.scrollElement(args))
                "type_text" -> guiTools.afterAction(guiTools.typeText(args))
                // 旧会话或旧入口仍可能请求这些工具名；保留执行路径，但不再出现在模型目录里。
                "input_text" -> guiTools.afterAction(guiTools.inputText(args))
                "replace_text" -> guiTools.afterAction(guiTools.replaceText(args))
                "clear_text" -> guiTools.afterAction(guiTools.clearText(args))
                "set_clipboard" -> textResult(guiTools.setClipboard(args))
                "get_clipboard" -> textResult(guiTools.getClipboard())
                "paste_text" -> guiTools.afterAction(guiTools.pasteText(args))
                "press_key" -> guiTools.afterAction(guiTools.pressKey(args))
                "wait" -> textResult(guiTools.waitMs(args))
                "wait_for_text" -> textResult(guiTools.waitForText(args))
                "wait_for_package" -> textResult(guiTools.waitForPackage(args))
                "open_system_panel" -> textResult(guiTools.openSystemPanel(args))
                in DEVICE_TOOL_NAMES ->
                    structuredDeviceTools.execute(toolCall.name, args)
                        ?: textResult(errorResult("UNKNOWN_TOOL", "未知设备工具"))
                "read_image" -> fileVisionTool { imageTools.readImage(args) }
                in AgentTerminalToolCatalog.names -> textResult(
                    terminalTool {
                        if (toolCall.name == "terminal" && args.optString("action").equals("check_root", ignoreCase = true)) {
                            checkRoot()
                        } else {
                            terminalController.executeTool(toolCall.name, args)
                        }
                    },
                )
                in AgentFileToolCatalog.names -> textResult(terminalTool { terminalController.fileTool(toolCall.name, args) })
                "memory_get" -> textResult(memoryGet(args))
                "memory_write" -> textResult(memoryWrite(args))
                "skills_list" -> textResult(skillTools.list(args))
                "skills_read" -> textResult(skillTools.read(args))
                "skills_read_resource" -> textResult(skillTools.readResource(args))
                "skills_list_curated" -> textResult(skillTools.listCurated())
                "skills_inspect_github" -> textResult(skillTools.inspectGitHub(args))
                "skills_install_from_github" -> textResult(skillTools.installFromGitHub(args))
                else -> textResult(
                    errorResult(
                        code = "UNKNOWN_TOOL",
                        message = "未知工具：${toolCall.name}",
                    ),
                )
            }
        }.getOrElse { throwable ->
            if (throwable is AgentRunCancelledException) throw throwable
            textResult(
                errorResult(
                    code = when (throwable) {
                        is InvalidToolArgumentException, is JSONException -> "INVALID_ARGUMENT"
                        is DeviceControlUnavailableException -> "ACCESSIBILITY_UNAVAILABLE"
                        is ActiveDisplayUnavailableException -> "DISPLAY_UNAVAILABLE"
                        else -> "TOOL_ERROR"
                    },
                    message = throwable.message ?: throwable.javaClass.simpleName,
                ),
            )
        }.let { result ->
            if (result.sensitive || !AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                result
            } else {
                result.copy(sensitive = true)
            }
        }

    private fun executeForegroundIntent(toolName: String, action: () -> JSONObject): JSONObject {
        fun execute(): JSONObject {
            if (closed.get()) throw AgentRunCancelledException()
            if (!isToolEnabled(toolName)) {
                return JSONObject(errorResult("TOOL_DISABLED", "工具 $toolName 已在工具列表中关闭，本次未执行"))
            }
            deviceToolPermissionError(toolName)?.let { return JSONObject(it.content) }
            return when (val decision = beforeToolExecution(toolName)) {
                ToolExecutionDecision.Allow -> {
                    guiTools.clearObservation()
                    action()
                }
                is ToolExecutionDecision.Reject -> JSONObject(errorResult(decision.code, decision.message))
            }
        }
        return guiControl?.withAction { execute() } ?: execute()
    }

    private fun deviceToolPermissionError(
        toolName: String,
    ): AgentModelClient.ToolResult? {
        val error = when {
            toolName in DEVICE_DIRECT_TOOL_NAMES && !deviceDirectToolsEnabled() ->
                "DEVICE_DIRECT_TOOLS_DISABLED" to "请先启用设备直达工具"
            toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES && !deviceSensitiveReadToolsEnabled() ->
                "DEVICE_SENSITIVE_READ_TOOLS_DISABLED" to "请先允许读取敏感设备信息"
            toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES && !deviceSensitiveActionToolsEnabled() ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作"
            else -> null
        } ?: return null
        return AgentModelClient.ToolResult(
            content = errorResult(error.first, error.second),
            sensitive = toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES ||
                toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES,
        )
    }

    private fun terminalTool(block: () -> String): String {
        if (!terminalToolsEnabled()) {
            return errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具")
        }
        return block()
    }

    private fun fileVisionTool(block: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (!terminalToolsEnabled()) {
            return textResult(errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具"))
        }
        return block()
    }

    private fun memoryToolPermissionError(toolName: String): AgentModelClient.ToolResult? {
        if (toolName == "memory_write" && !memoryWritable) {
            return AgentModelClient.ToolResult(
                content = errorResult("REAL_MEMORY_READ_ONLY", "角色会话的现实记忆只读；剧情请使用角色记忆工具"),
                sensitive = true,
            )
        }
        if (toolName !in MEMORY_TOOL_NAMES || memoryToolsEnabled()) return null
        return AgentModelClient.ToolResult(
            content = errorResult("MEMORY_DISABLED", "记忆已在设置中关闭"),
            sensitive = true,
        )
    }

    private fun memoryGet(args: JSONObject): String = try {
        val result = AgentMemoryRepository.read(
            query = args.optString("query").takeIf(String::isNotBlank),
            startLine = args.optInt("start_line", 1),
            maxChars = args.optInt("max_chars", 12_000),
        )
        JSONObject()
            .put("ok", true)
            .put("revision", result.snapshot.revision)
            .put("bytes", result.snapshot.byteSize)
            .put("line_count", result.snapshot.lineCount)
            .put("start_line", result.startLine ?: JSONObject.NULL)
            .put("end_line", result.endLine ?: JSONObject.NULL)
            .put("matched_lines", result.matchedLines)
            .put("has_more", result.hasMore)
            .put("content", result.content)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆读取失败")
    }

    private fun memoryWrite(args: JSONObject): String = try {
        val revision = args.getString("revision")
        val mutation = when (args.getString("mode")) {
            "replace_range" -> AgentMemoryMutation.ReplaceRange(
                revision = revision,
                startLine = args.getInt("start_line"),
                endLine = args.getInt("end_line"),
                content = args.requiredText("content"),
            )
            "append" -> AgentMemoryMutation.Append(
                revision = revision,
                content = args.requiredText("content"),
            )
            "clear" -> AgentMemoryMutation.Clear(revision)
            else -> error("不支持的记忆写入模式")
        }
        when (val result = AgentMemoryRepository.mutate(mutation)) {
            is AgentMemoryWriteResult.Success -> JSONObject()
                .put("ok", true)
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
            is AgentMemoryWriteResult.Conflict -> JSONObject()
                .put("ok", false)
                .put("code", "MEMORY_CONFLICT")
                .put("message", "记忆已发生变化，请先调用 memory_get 获取最新内容")
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
        }
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆写入失败")
    }

    private fun browserDevtools(args: JSONObject, toolCallId: String): AgentModelClient.ToolResult {
        if (!browserToolsEnabled()) {
            return textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页浏览工具"))
        }
        // 会话侧统一走 "devtools" 这一个 action，复用它的锁、取消与 epoch 语义；
        // 子动作名放在 devtoolsAction 里，避免为浏览器再造一条执行管线。
        val mapped = JSONObject(args.toString()).apply {
            put("devtoolsAction", args.optString("action"))
            put("action", "devtools")
        }
        val result = sessionFor(args).execute(
            context = context,
            args = mapped,
            runId = browserRunId,
            toolCallId = toolCallId,
            checkCancellation = { if (closed.get()) throw AgentRunCancelledException() },
        )
        return AgentModelClient.ToolResult(content = result.content)
    }

    /** 按 `tab` 参数选标签页；缺省落到主会话，行为与引入标签前一致。 */
    private fun sessionFor(args: JSONObject): AgentBrowserSession {
        val raw = args.optString("tab").trim()
        if (raw.isBlank()) return browserSessionRef.value
        if (!tabNamePattern.matches(raw)) {
            throw InvalidToolArgumentException("tab 名只能用字母、数字、-、_，长度 1-32")
        }
        val key = raw.lowercase(Locale.ROOT)
        return browserTabs.getOrPut(key) { AgentBrowserSession().also { it.initialize(context) } }
    }

    private fun listBrowserTabs(): AgentModelClient.ToolResult =
        textResult(
            JSONObject()
                .put("ok", true)
                .put("tool", "browser_use")
                .put("action", "list_tabs")
                .put(
                    "tabs",
                    JSONArray().also { array -> browserTabs.keys.sorted().forEach { array.put(it) } },
                )
                .put("main_tab", "默认标签（不带 tab 参数）始终存在")
                .toString(),
        )

    private fun closeBrowserTab(args: JSONObject): AgentModelClient.ToolResult {
        val key = args.optString("tab").trim().lowercase(Locale.ROOT)
        if (key.isBlank()) {
            return textResult(errorResult("INVALID_ARGUMENT", "close_tab 需要 tab 名；主标签不能关闭"))
        }
        val session = browserTabs.remove(key)
            ?: return textResult(errorResult("TAB_NOT_FOUND", "没有名为 $key 的标签"))
        Handler(Looper.getMainLooper()).post {
            runCatching { session.releaseIdlePage(dispose = true) }
        }
        return textResult(JSONObject().put("ok", true).put("closed", key).toString())
    }

    private fun browserUse(args: JSONObject, toolCallId: String): AgentModelClient.ToolResult {
        if (!browserToolsEnabled()) {
            return textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页浏览工具"))
        }
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        if (action == "list_tabs") return listBrowserTabs()
        if (action == "close_tab") return closeBrowserTab(args)
        val result = sessionFor(args).execute(
            context = context,
            args = args,
            runId = browserRunId,
            toolCallId = toolCallId,
            checkCancellation = { if (closed.get()) throw AgentRunCancelledException() },
        )
        return AgentModelClient.ToolResult(
            content = result.content,
            images = result.images.map { image ->
                AgentModelClient.ModelImage(
                    reference = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = "agent_browser",
                    preserveOriginal = true,
                )
            },
        )
    }

    private fun currentActiveDisplayId(): Int {
        val id = activeDisplayId
        if (!CanvasRoutingPolicy.isVirtualDisplay(id)) return Display.DEFAULT_DISPLAY
        val vd = VirtualDisplayController.snapshots.value
        return if (vd.active && vd.displayId == id) {
            id
        } else {
            publishedObservation.set(PublishedObservation())
            throw ActiveDisplayUnavailableException()
        }
    }

    private fun checkCancellation() {
        if (closed.get() || Thread.currentThread().isInterrupted) throw AgentRunCancelledException()
    }

    private fun publishObservation(observation: PublishedObservation) {
        checkCancellation()
        publishedObservation.set(observation)
        if (closed.get() || Thread.currentThread().isInterrupted) {
            publishedObservation.compareAndSet(observation, PublishedObservation())
            throw AgentRunCancelledException()
        }
    }

    private fun observeScreen(args: JSONObject, generation: Long?): AgentModelClient.ToolResult {
        publishedObservation.set(PublishedObservation())
        val startedAt = SystemClock.elapsedRealtime()
        val options = AgentScreenObservationContract.resolve(args)
        val displayId = currentActiveDisplayId()
        val observation = screenObservationProvider?.invoke(options)
            ?: deviceController.observe(
                includeScreenshot = options.includeScreenshot,
                includeUiTree = options.includeUiTree,
                maxNodes = options.maxNodes,
                displayId = displayId,
            )
        publishObservation(
            PublishedObservation(
                elements = observation.elementObservation,
                coordinateSpace = observation.coordinateSpace,
                generation = generation.takeIf { JSONObject(observation.content).optBoolean("ok") },
            ),
        )
        logger.debug {
            "Agent local tool action=observe_screen outcome=completed " +
                "display=$displayId observation=${observation.elementObservation?.id} " +
                "nodes=${observation.elementObservation?.nodes?.size ?: 0} " +
                "image=${observation.image?.bytes ?: 0} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "coordinate=${observation.coordinateSpace?.summary()}"
        }
        return AgentModelClient.ToolResult(
            content = observation.content,
            images = listOfNotNull(observation.image)
        )
    }

    /**
     * 成功的 GUI 动作附带一次轻量观察：只读 UI 树、不截图，节点数减半。
     * 模型据此确认动作生效并直接用新的 observation_id 继续操作，不必再单独 observe_screen；
     * 失败或结果未知的动作不附带，保持"先重新观察"的既有约束。
     */
    private fun afterAction(raw: String): AgentModelClient.ToolResult {
        checkCancellation()
        val result = runCatching { JSONObject(raw) }.getOrNull()
        if (result == null || !result.optBoolean("ok")) return textResult(raw)
        val before = publishedObservation.get().elements
        val after = runCatching {
            val displayId = currentActiveDisplayId()
            screenObservationProvider?.invoke(AFTER_ACTION_OPTIONS)
                ?: deviceController.observe(
                    includeScreenshot = false,
                    includeUiTree = true,
                    maxNodes = AFTER_ACTION_OPTIONS.maxNodes,
                    displayId = displayId,
                )
        }.getOrElse { throwable ->
            publishedObservation.set(PublishedObservation())
            checkCancellation()
            if (throwable is AgentRunCancelledException) throw throwable
            logger.debug { "Agent local tool after-action observation failed: type=${throwable.javaClass.simpleName}" }
            RootShellDeviceController.Observation(
                content = JSONObject()
                    .put("ok", false)
                    .put("ui_tree_status", "query_failed")
                    .put("code", if (throwable is ActiveDisplayUnavailableException) "DISPLAY_UNAVAILABLE" else "OBSERVATION_FAILED")
                    .put("ui_tree_note", "动作已执行，但后续观察失败；旧节点与坐标已失效，请先重新观察，不要重复动作")
                    .toString(),
                image = null,
                coordinateSpace = null,
                elementObservation = null,
            )
        }
        // 新快照取代动作前的快照：旧 index 在界面变化后本就不可靠，继续保留只会让模型误用。
        publishObservation(
            if (after.elementObservation == null) PublishedObservation()
            else publishedObservation.get().copy(elements = after.elementObservation, coordinateSpace = after.coordinateSpace),
        )
        result.put("after", AgentAfterActionSummary.build(before, after))
        return textResult(result.toString())
    }

    private fun tap(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val point = convertPoint(
            x = args.getInt("x"),
            y = args.getInt("y"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        return clearOverlay(displayId, point) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            }
            showTap(point.x, point.y, displayId)
            deviceController.tap(point.x, point.y, displayId)
        }
    }

    private fun tapArea(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val x1 = args.getInt("x1")
        val y1 = args.getInt("y1")
        val x2 = args.getInt("x2")
        val y2 = args.getInt("y2")
        val coordinateSpace = args.optString("coordinate_space")
        val first = convertPoint(x1, y1, coordinateSpace, displayId)
        val second = convertPoint(x2, y2, coordinateSpace, displayId)
        val point = ScreenPoint(
            x = ((first.x.toLong() + second.x.toLong()) / 2L).toInt(),
            y = ((first.y.toLong() + second.y.toLong()) / 2L).toInt(),
        )
        return clearOverlay(displayId, point) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            }
            showTap(point.x, point.y, displayId)
            deviceController.tap(point.x, point.y, displayId)
        }
    }

    private fun tapElement(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = clearOverlay(displayId, ScreenPoint(node.centerX, node.centerY)) {
            deviceController.tapElement(observation, index)
        }
        if (result.isOkJson()) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            }
            showTap(node.centerX, node.centerY, displayId)
        }
        return result
    }

    private fun longPressElement(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        val durationMs = args.optInt("duration_ms", 800)
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = clearOverlay(displayId, ScreenPoint(node.centerX, node.centerY)) {
            deviceController.longPressElement(observation, index, durationMs)
        }
        if (result.isOkJson()) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            }
            showLongPress(node.centerX, node.centerY, durationMs, displayId)
        }
        return result
    }

    private fun longPress(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val point = convertPoint(
            x = args.getInt("x"),
            y = args.getInt("y"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val durationMs = args.optInt("duration_ms", 800)
        return clearOverlay(displayId, point) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            }
            showLongPress(point.x, point.y, durationMs, displayId)
            deviceController.longPress(point.x, point.y, durationMs, displayId)
        }
    }

    private fun swipe(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val start = convertPoint(
            x = args.getInt("x1"),
            y = args.getInt("y1"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val end = convertPoint(
            x = args.getInt("x2"),
            y = args.getInt("y2"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val durationMs = args.optInt("duration_ms", 500)
        return clearOverlay(displayId, start, end) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.SWIPE)
            }
            showSwipe(start.x, start.y, end.x, end.y, durationMs, displayId)
            deviceController.swipe(
                start.x,
                start.y,
                end.x,
                end.y,
                durationMs,
                displayId,
            )
        }
    }

    private fun scrollElement(args: JSONObject): String {
        val observation = requireElementObservation(args) ?: return observationError(args)
        return deviceController.scrollElement(
            observation = observation,
            index = args.optInt("index", -1),
            direction = args.optString("direction"),
            amount = args.optString("amount"),
        )
    }

    /**
     * 统一的文本输入：replace（默认）整体替换为 text，text 为空即清空；append 在光标处插入。
     * 指定 index 时先确认节点来自最近一次观察；不指定时作用于当前输入焦点。
     * submit=true 在写入成功后按输入法回车，用于搜索、发送等提交动作。
     */
    private fun typeText(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val text = args.requiredText("text")
        val mode = args.optString("mode", "replace").trim().lowercase(Locale.ROOT).ifBlank { "replace" }
        val written = when (mode) {
            "replace" -> replaceText(args)
            "append" -> {
                if (text.isEmpty()) return errorResult("INVALID_ARGUMENT", "append 模式的 text 不能为空")
                if (args.optNullableInt("index") != null) {
                    return errorResult("INVALID_ARGUMENT", "append 只作用于当前输入焦点；要写入指定输入框请用 mode=replace")
                }
                // 长文本走选区插入加粘贴回退，比逐字键入更稳定。
                if (text.length > TYPE_TEXT_INCREMENTAL_CHARS) pasteText(args) else deviceController.inputText(text, displayId)
            }
            else -> return errorResult("INVALID_ARGUMENT", "mode 只能是 replace 或 append")
        }
        val json = runCatching { JSONObject(written) }.getOrNull() ?: return written
        json.put("tool", "type_text").put("mode", mode)
        if (!json.optBoolean("ok") || !args.optBoolean("submit")) return json.toString()
        val submitted = runCatching { JSONObject(deviceController.pressKey("ENTER", displayId)) }.getOrNull()
        json.put("submitted", submitted?.optBoolean("ok") == true)
        if (submitted?.optBoolean("ok") != true) {
            // 文本已写入但提交失败：整体仍算成功，避免模型重复输入；由 submitted=false 提示单独补按回车或点按钮。
            json.put("submit_error", submitted?.optString("code").orEmpty().ifBlank { "SUBMIT_FAILED" })
        }
        return json.toString()
    }

    private fun inputText(args: JSONObject): String {
        val text = args.requiredText("text")
        if (text.length > 1_000) {
            return errorResult("TEXT_TOO_LONG", "input_text 最多支持 1000 个字符")
        }
        return when (args.optString("mode", "append").lowercase(Locale.ROOT)) {
            "replace" -> replaceText(args)
            "paste" -> pasteText(args)
            else -> deviceController.inputText(text, currentActiveDisplayId())
        }
    }

    private fun replaceText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.replaceText(
            text = args.requiredText("text"),
            index = index,
            observation = observation,
            displayId = currentActiveDisplayId(),
        )
    }

    private fun clearText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.clearText(index = index, observation = observation, displayId = currentActiveDisplayId())
    }

    private fun setClipboard(args: JSONObject): String =
        deviceController.clipboardSet(requireContext(), args.requiredText("text"))

    private fun getClipboard(): String =
        deviceController.clipboardGet(requireContext())

    private fun pasteText(args: JSONObject): String =
        deviceController.pasteText(args.requiredText("text"), currentActiveDisplayId())

    private fun waitForText(args: JSONObject): String =
        deviceController.waitForText(
            text = args.requiredText("text"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            includeDesc = args.optBoolean("include_desc", true),
            matchMode = args.optString("match", "contains"),
            displayId = currentActiveDisplayId(),
        )

    private fun waitForPackage(args: JSONObject): String =
        deviceController.waitForPackage(
            packageName = args.optString("package_name"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            displayId = currentActiveDisplayId(),
        )

    /**
     * 坐标系必须显式声明。旧版按"上一次观察是否带截图"隐式切换默认值，模型无从得知，
     * 经常把截图像素当成屏幕像素；缺省时直接报参数错误，让模型在下一步改正。
     */
    private fun convertPoint(
        x: Int,
        y: Int,
        coordinateSpace: String,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): ScreenPoint {
        val space = publishedObservation.get().coordinateSpace
        val requestedSpace = coordinateSpace.trim().lowercase(Locale.ROOT)
        if (requestedSpace.isBlank()) {
            throw InvalidToolArgumentException(
                "缺少 coordinate_space：看截图定位用 normalized（0–999），坐标来自 ui_nodes 用 screen",
            )
        }
        if (requestedSpace == "normalized") {
            if (x !in 0..NORMALIZED_MAX || y !in 0..NORMALIZED_MAX) {
                throw InvalidToolArgumentException("normalized 坐标必须在 0–$NORMALIZED_MAX 之间：($x,$y)")
            }
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions(displayId)
            return ScreenPoint(
                x = (x.toLong() * (width - 1) / NORMALIZED_MAX).toInt(),
                y = (y.toLong() * (height - 1) / NORMALIZED_MAX).toInt(),
            )
        }
        if (requestedSpace == "screen") {
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions(displayId)
            if (x !in 0 until width || y !in 0 until height) {
                throw InvalidToolArgumentException(
                    "屏幕坐标超出范围：($x,$y) not in ${width}x$height",
                )
            }
            return ScreenPoint(x, y)
        }
        if (requestedSpace != "screenshot") {
            throw InvalidToolArgumentException("coordinate_space 只能是 normalized、screen 或 screenshot")
        }
        if (space == null) {
            throw InvalidToolArgumentException(
                "最近一次观察没有附图，没有 screenshot 坐标系；看截图定位请用 normalized，或先带截图重新观察",
            )
        }
        val point = runCatching { space.fromScreenshot(x, y) }
            .getOrElse { throwable ->
                throw InvalidToolArgumentException(
                    throwable.message ?: "截图坐标超出范围",
                )
            }
        return ScreenPoint(point.x, point.y)
    }

    private fun searchApps(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "query 不能为空")
        }
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val apps = findAppsByName(query, includeSystem).take(limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_apps")
            .put("query", query)
            .put("apps", apps.toJsonArray())
            .toString()
    }

    private fun launchApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim().ifBlank { null }
        val appName = args.optString("app_name").trim().ifBlank { null }
        val requestedDisplay = CanvasRoutingPolicy.RequestedDisplay.parse(
            args.optString("display", "auto"),
        ) ?: return errorResult("INVALID_ARGUMENT", "display 仅支持 auto、virtual 或 main")

        val app = if (packageName != null) {
            findAppByPackage(packageName) ?: AppInfo(packageName = packageName, appName = appName ?: packageName)
        } else {
            if (appName == null) {
                return errorResult("INVALID_ARGUMENT", "package_name 和 app_name 至少提供一个")
            }
            val matches = findAppsByName(appName, includeSystem = false)
            val exactMatches = matches.filter { it.appName.equals(appName, ignoreCase = true) }
            when {
                exactMatches.size == 1 -> exactMatches.single()
                matches.size == 1 -> matches.single()
                matches.isEmpty() -> return errorResult(
                    code = "APP_NOT_FOUND",
                    message = "未找到应用：$appName"
                )
                else -> return JSONObject()
                    .put("ok", false)
                    .put("code", "AMBIGUOUS_APP")
                    .put("message", "匹配到多个应用，请指定 package_name")
                    .put("candidates", matches.take(10).toJsonArray())
                    .toString()
            }
        }

        val context = requireContext()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            return errorResult(
                code = "APP_NOT_LAUNCHABLE",
                message = "应用不可启动或未安装：${app.packageName}"
            )
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        if (virtualDisplayEnabled() && requestedDisplay != CanvasRoutingPolicy.RequestedDisplay.MAIN && !rootAvailable()) {
            io.github.mangi.eta.agent.device.RootAccess.awaitOrRetryIfPreviouslyGranted(context)
        }
        val mainForegroundPkg = AgentAccessibilityService.current()?.currentPackageName(Display.DEFAULT_DISPLAY)
        val launchPlan = CanvasRoutingPolicy.decideLaunchPlan(
            virtualDisplayEnabled = virtualDisplayEnabled(),
            backendAvailable = VirtualDisplayController.isBackendAvailable(rootAvailable(), lsposedAvailable()),
            requested = requestedDisplay,
            targetPackage = app.packageName,
            foregroundPackageOnMainDisplay = mainForegroundPkg,
            ownPackage = context.packageName,
        )

        val launchedVirtualId = if (launchPlan.target == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            VirtualDisplayController.launchAppOnVirtualDisplay(
                context = context,
                packageName = app.packageName,
                appName = app.appName,
                launchIntent = launchIntent,
                rootAvailable = rootAvailable,
                checkCancellation = ::checkCancellation,
            )
        } else {
            null
        }

        var canvasFallback: String? = null
        val finalDisplayId = if (launchedVirtualId != null && CanvasRoutingPolicy.isVirtualDisplay(launchedVirtualId)) {
            activeDisplayId = launchedVirtualId
            launchedVirtualId
        } else {
            checkCancellation()
            activeDisplayId = Display.DEFAULT_DISPLAY
            context.startActivity(launchIntent)
            if (launchPlan.target == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
                // 要求虚拟屏却落到主屏时别装作成功：模型和用户都需要知道画布变了。
                canvasFallback = "VIRTUAL_SCREEN_FAILED"
                logger.warn("Agent local tool launch_app fell back to main: virtual display unavailable")
            }
            Display.DEFAULT_DISPLAY
        }
        val finalCanvas = if (CanvasRoutingPolicy.isVirtualDisplay(finalDisplayId)) {
            CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName
        } else {
            CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName
        }
        val canvasReason = if (finalCanvas == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName) {
            launchPlan.reason
        } else if (launchPlan.target == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            "virtual_create_failed"
        } else {
            launchPlan.reason
        }

        val verification = AppLaunchVerification.awaitWindow(
            packageName = app.packageName,
            displayId = finalDisplayId,
            query = { remainingMillis ->
                AgentAccessibilityService.current()?.observeNodeSnapshot(
                    maxNodes = 1,
                    displayId = finalDisplayId,
                    includeNodes = false,
                    timeoutMillis = remainingMillis,
                ) ?: AgentAccessibilityService.NodeSnapshotObservation(
                    status = AgentAccessibilityService.NodeSnapshotStatus.SERVICE_UNAVAILABLE,
                    snapshot = null,
                    window = null,
                )
            },
            checkCancellation = ::checkCancellation,
        )
        logger.info(
            "Agent local tool action=launch_app outcome=${verification.launchStatus} " +
                "canvas=$finalCanvas reason=$canvasReason display=$finalDisplayId " +
                "root=${io.github.mangi.eta.agent.device.RootAccess.state.value.status.name.lowercase()} " +
                "lsposed=${lsposedAvailable()}",
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "launch_app")
            .put("app_name", app.appName)
            .put("package_name", app.packageName)
            .put("canvas", finalCanvas)
            .put("canvas_reason", canvasReason)
            .put("display_id", finalDisplayId)
            .put("launch_status", verification.launchStatus)
            .put("window_observation", verification.toJson())
            .apply {
                canvasFallback?.let { put("canvas_fallback", it) }
                if (!verification.confirmed) {
                    put("note", "启动请求已提交，但尚未确认目标窗口；请先观察当前画布，不要直接重放启动或点击")
                }
            }
            .toString()
    }

    private fun presentToUser(args: JSONObject): String {
        val context = requireContext()
        val reason = args.optString("reason").trim()
        val vdSnapshot = VirtualDisplayController.snapshots.value
        val wasVirtual = vdSnapshot.active || CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId)
        val presented = if (wasVirtual) {
            VirtualDisplayController.handoffToMainDisplay(context, rootAvailable)
        } else {
            true
        }
        activeDisplayId = Display.DEFAULT_DISPLAY
        return JSONObject()
            .put("ok", true)
            .put("tool", "present_to_user")
            .put("canvas", CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName)
            .put("display_id", Display.DEFAULT_DISPLAY)
            .put("presented", presented)
            .apply {
                if (vdSnapshot.appName.isNotBlank()) put("app_name", vdSnapshot.appName)
                if (vdSnapshot.packageName.isNotBlank()) put("package_name", vdSnapshot.packageName)
                if (reason.isNotBlank()) put("reason", reason)
            }
            .toString()
    }

    private fun openUri(args: JSONObject): String {
        val uriText = args.optString("uri").trim()
        if (uriText.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 不能为空")
        }
        val uri = Uri.parse(uriText)
        if (uri.scheme.isNullOrBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 缺少 scheme")
        }
        val context = requireContext()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!HookSupport.resolvesActivity(context, intent)) {
            return errorResult("NO_ACTIVITY", "没有应用可以处理该 URI")
        }
        if (virtualDisplayEnabled() && !rootAvailable()) {
            io.github.mangi.eta.agent.device.RootAccess.awaitOrRetryIfPreviouslyGranted(context)
        }
        val resolvedPkg = context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        val mainForegroundPkg = AgentAccessibilityService.current()?.currentPackageName(Display.DEFAULT_DISPLAY)
        val launchPlan = CanvasRoutingPolicy.decideLaunchPlan(
            virtualDisplayEnabled = virtualDisplayEnabled(),
            backendAvailable = VirtualDisplayController.isBackendAvailable(rootAvailable(), lsposedAvailable()),
            requested = CanvasRoutingPolicy.RequestedDisplay.AUTO,
            targetPackage = resolvedPkg,
            foregroundPackageOnMainDisplay = mainForegroundPkg,
            ownPackage = context.packageName,
        )
        val virtualId = if (launchPlan.target == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            VirtualDisplayController.openUriOnVirtualDisplay(
                context = context,
                intent = intent,
                targetLabel = uri.host ?: uri.scheme.orEmpty(),
                rootAvailable = rootAvailable,
                checkCancellation = ::checkCancellation,
            )
        } else {
            null
        }
        val finalDisplayId = if (virtualId != null && CanvasRoutingPolicy.isVirtualDisplay(virtualId)) {
            activeDisplayId = virtualId
            virtualId
        } else {
            checkCancellation()
            activeDisplayId = Display.DEFAULT_DISPLAY
            context.startActivity(intent)
            Display.DEFAULT_DISPLAY
        }
        val finalCanvas = if (CanvasRoutingPolicy.isVirtualDisplay(finalDisplayId)) {
            CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName
        } else {
            CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName
        }
        val canvasReason = if (finalCanvas == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName) {
            launchPlan.reason
        } else if (launchPlan.target == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            "virtual_create_failed"
        } else {
            launchPlan.reason
        }
        logger.info("Agent local tool action=open_uri outcome=started canvas=$finalCanvas reason=$canvasReason")
        return JSONObject()
            .put("ok", true)
            .put("tool", "open_uri")
            .put("scheme", uri.scheme?.lowercase(Locale.ROOT))
            .put("canvas", finalCanvas)
            .put("canvas_reason", canvasReason)
            .put("display_id", finalDisplayId)
            .also { result ->
                if (uri.scheme.equals("https", true)) {
                    result.put("display_uri", uriText)
                }
            }
            .toString()
    }

    private fun checkRoot(): String {
        val probe = RootAccess.request(requireContext())
        runBlocking {
            while (probe.isActive && !closed.get()) delay(100)
        }
        if (closed.get()) return errorResult("CANCELLED", "已取消等待 Root 检测")
        val state = RootAccess.state.value
        return JSONObject().put("ok", true).put("tool", "terminal").put("action", "check_root")
            .put("root_available", state.isGranted).put("status", state.status.name.lowercase(Locale.ROOT))
            .put("checking", state.isChecking).put("su_present", state.suPresent)
            .put("uid", if (state.isGranted) 0 else JSONObject.NULL)
            .put(
                "message",
                if (state.isGranted) {
                    "已确认 Root 授权；后续新命令默认使用 Root"
                } else {
                    "尚未确认 Root 授权；请根据 status 检查授权或检测结果，默认 user 不表示终端不支持 Root"
                },
            )
            .toString()
    }

    private fun checkCancellation() {
        if (closed.get() || Thread.currentThread().isInterrupted) throw AgentRunCancelledException()
    }

    private fun requireContext(): Context =
        AgentAppContext.resolve()
            ?: error("无法获取 Android 进程 Context")

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private fun textResult(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content)

    private companion object {
        val DEVICE_DIRECT_TOOL_NAMES = AgentPhoneToolCatalog.direct + setOf(
            "set_alarm",
            "set_timer",
            "device_status",
            "inspect_app",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "media_control",
            "set_volume",
        )
        val DEVICE_SENSITIVE_READ_TOOL_NAMES = PersonalSearchTools.names + AgentPhoneToolCatalog.reads + setOf(
            "search_notes", "search_system_memories",
            "get_setting",
            "wifi_credentials",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "search_clipboard_history",
            "get_health_summary",
            "read_sms_code",
            "get_logcat",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_coloros_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_coloros_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
        )
        val DEVICE_SENSITIVE_ACTION_TOOL_NAMES = AgentPhoneToolCatalog.writes + setOf(
            "set_setting",
            "set_device_state",
            "app_state_control",
        )
        val DEVICE_TOOL_NAMES =
            DEVICE_DIRECT_TOOL_NAMES + DEVICE_SENSITIVE_READ_TOOL_NAMES +
                DEVICE_SENSITIVE_ACTION_TOOL_NAMES
        val MEMORY_TOOL_NAMES = setOf("memory_get", "memory_write")
    }
}
