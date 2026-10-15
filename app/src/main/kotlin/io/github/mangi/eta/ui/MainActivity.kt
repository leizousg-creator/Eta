package io.github.mangi.eta.ui

import io.github.mangi.eta.ui.voice.ACTION_SPEECH_SETTINGS
import android.app.UiModeManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.hook.vivo.VivoHandoff
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppRoot
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.mangi.eta.ui.app.installStartupSplash
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var assistantConversationKey by mutableStateOf<String?>(null)
    private var assistantConversationSource by mutableStateOf(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE)
    private var requestedConversationId by mutableStateOf<String?>(null)
    private var appliedPredictiveBackEnabled = true
    private var speechSettingsRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var contentReady = false
        installStartupSplash { contentReady }
        enableEdgeToEdge()
        updateAssistantHandoff(intent)
        handleShareIntent(intent)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)

                LaunchedEffect(appearance.themeMode) {
                    updateApplicationNightMode(appearance.themeMode)
                }

                LaunchedEffect(appearance.predictiveBackEnabled) {
                    val enabled = appearance.predictiveBackEnabled
                    if (enabled != appliedPredictiveBackEnabled &&
                        PredictiveBackController.apply(applicationInfo, enabled)
                    ) {
                        appliedPredictiveBackEnabled = enabled
                        recreateWithoutTransition()
                    }
                }

                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = ::updateSystemBars,
                ) {
                    AgentAppRoot(
                        assistantConversationKey = assistantConversationKey,
                        assistantConversationSource = assistantConversationSource,
                        requestedConversationId = requestedConversationId,
                        onRequestedConversationOpened = { requestedConversationId = null; intent?.action = null },
                        openSpeechSettings = speechSettingsRequested,
                        onSpeechSettingsOpened = { speechSettingsRequested = false; intent?.action = null },
                        onAssistantConversationOpened = { opened ->
                            assistantConversationKey = null
                            if (opened && assistantConversationSource == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
                                EtaAssistantOverlayService.notifyHandoffReady(this@MainActivity)
                            }
                        },
                    )
                    io.github.mangi.eta.agent.display.VirtualScreenAppConflictDialog()
                }
            }
            contentReady = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateAssistantHandoff(intent)
        handleShareIntent(intent)
        // Activity 被复用（栈里已有实例）时 AgentAppRoot 的首帧效应不会重跑；
        // 除了「打开指定会话」由 updateAssistantHandoff 精确清，其他再次进入都按「回到 Eta」整体清未读。
        if (intent.action != ACTION_OPEN_CONVERSATION_ID) {
            runCatching {
                startService(
                    Intent(this, AgentRuntimeService::class.java)
                        .setAction(AgentRuntimeService.ACTION_CLEAR_READ),
                )
            }
        }
    }

    /** 系统分享面板送进来的文字／图片，先暂存起来，界面就绪后由 AgentAppRoot 取走。 */
    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val text = intent.shareText()
        val images = intent.shareUris().map { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            uri.toString()
        }
        io.github.mangi.eta.core.EtaTrace.log(
            "share.intent",
            "收到分享：文字=${text?.length ?: 0} 字，图片=${images.size} 张",
        )
        io.github.mangi.eta.ui.app.ShareIntake.offer(text, images)
        intent.action = null
    }

    /**
     * 分享进来的文字：有的应用塞 CharSequence，有的塞 String，还有的只挂在 ClipData 上；
     * 只读一次 EXTRA_TEXT 会漏，表现就是「提示分享成功、输入框却是空的」。
     */
    private fun Intent.shareText(): String? {
        getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        @Suppress("DEPRECATION")
        getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
        val clip = clipData ?: return null
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            item.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            item.htmlText?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * 分享进来的图片／文件：EXTRA_STREAM 可能是单个 Uri，也可能是 ArrayList（多选分享），
     * 有的应用只放进 ClipData；三种来源都要收，否则就是「分享成功但没图」。
     */
    private fun Intent.shareUris(): List<android.net.Uri> {
        val uris = linkedSetOf<android.net.Uri>()
        runCatching {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        }.getOrNull()?.forEach { uri -> uri?.let(uris::add) }
        runCatching {
            getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        }.getOrNull()?.let(uris::add)
        clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let(uris::add)
            }
        }
        return uris.toList()
    }

    private fun updateAssistantHandoff(intent: Intent?) {
        if (intent?.action == ACTION_VIEW_EXECUTION) {
            assistantConversationSource = intent.getStringExtra(EXTRA_EXECUTION_SOURCE)
                ?.takeIf { it in setOf(
                    VivoHandoff.SOURCE, AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                    AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "automation",
                ) } ?: AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
            assistantConversationKey = intent.getStringExtra(
                EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY,
            )?.takeIf(String::isNotBlank)
            intent.action = null
            if (VirtualScreenSession.isActive()) {
                startActivity(Intent(this, VirtualScreenViewerActivity::class.java))
            }
            return
        }
        if (intent?.action == ACTION_SPEECH_SETTINGS) {
            speechSettingsRequested = true
            return
        }
        if (intent?.action == ACTION_OPEN_CONVERSATION_ID) {
            val openedId = intent.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf(String::isNotBlank)
            requestedConversationId = openedId
            // CLEAR_TOP 复用本 Activity 时不重建组合，AgentAppRoot 那次清未读不会重跑；
            // 这里直接把「点开的这条会话」标成已读，胶囊角标与「最新回复」立刻同步。
            openedId?.let { conversationId ->
                runCatching {
                    startService(
                        Intent(this, AgentRuntimeService::class.java)
                            .setAction(AgentRuntimeService.ACTION_MARK_READ)
                            .putExtra(
                                AgentRuntimeService.EXTRA_MARK_READ_CONVERSATION_ID,
                                conversationId,
                            ),
                    )
                }
            }
            return
        }
        if (intent?.action != EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION) return
        assistantConversationSource = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        assistantConversationKey = intent.getStringExtra(
            EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY,
        )?.takeIf(String::isNotBlank)
    }

    private fun updateApplicationNightMode(themeMode: AppearanceThemeMode) {
        val mode = when (themeMode) {
            AppearanceThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            AppearanceThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            // 应用级 AUTO 清除夜间模式覆盖，恢复跟随系统。
            AppearanceThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
        getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    private fun updateSystemBars(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_VIEW_EXECUTION = "io.github.mangi.eta.action.VIEW_EXECUTION"
        const val EXTRA_EXECUTION_SOURCE = "io.github.mangi.eta.extra.EXECUTION_SOURCE"
        /** 运行浮层的结果卡片回到本体：App 发起的 run 带会话 ID，直接打开该会话；缺省时只回到首页。 */
        const val ACTION_OPEN_CONVERSATION_ID = "io.github.mangi.eta.action.OPEN_CONVERSATION_ID"
        const val EXTRA_CONVERSATION_ID = "io.github.mangi.eta.extra.CONVERSATION_ID"
    }
}
