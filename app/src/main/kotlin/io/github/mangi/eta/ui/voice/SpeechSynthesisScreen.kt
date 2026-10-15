package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.EtaOverlayDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeechSynthesisScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SpeechSettingsStore(context, scope) }
    SpeechLifecycle { store.stop() }
    val settings = store.settings

    MiuixScaffoldPage(
        title = stringResource(R.string.speech_synthesis_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!store.loaded) {
            item(key = "loading") { SpeechLoadingState(store.message) }
            return@MiuixScaffoldPage
        }
        item(key = "service_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.speech_group_service))
        }
        item(key = "service") {
            EtaPreferenceGroup {
                EtaOverlayDropdownPreference(
                    title = stringResource(R.string.speech_service_and_model),
                    items = TtsProvider.entries.map { it.label() },
                    selectedIndex = settings.tts.ordinal,
                    onSelectedIndexChange = { index ->
                        TtsProvider.entries.getOrNull(index)?.let { store.edit(settings.copy(tts = it)) }
                    },
                )
            }
        }
        if (settings.tts == TtsProvider.QWEN || settings.tts == TtsProvider.DOUBAO) {
            item(key = "connection") {
                SpeechConnectionSection(store, synthesis = true)
            }
            item(key = "voice") {
                SpeechVoiceSection(store)
            }
        }
        if (settings.tts == TtsProvider.SYSTEM) {
            item(key = "tone") { SpeechToneSection(store) }
        }
        if (settings.tts != TtsProvider.NONE) {
            item(key = "preview") { SpeechPreviewSection(store) }
        }
        item(key = "save") {
            SpeechSaveBlock(store) {
                if (store.settings.tts != TtsProvider.NONE) {
                    validateSpeechSettings(store.settings, store.credentials, synthesis = true)
                }
            }
        }
    }
}

/** 语速与音调只作用于手机自带语音：云端合成接口不接受这两个参数，因此只在系统语音下出现。 */
@Composable
private fun SpeechToneSection(store: SpeechSettingsStore) {
    val settings = store.settings
    val labels = remember {
        SpeechSettings.SPEECH_TONE_OPTIONS.map { String.format(Locale.US, "%.2f×", it) }
    }
    Column {
        EtaPreferenceGroupTitle(stringResource(R.string.speech_group_tone))
        EtaPreferenceGroup {
            EtaOverlayDropdownPreference(
                title = stringResource(R.string.speech_rate),
                items = labels,
                selectedIndex = SpeechSettings.SPEECH_TONE_OPTIONS.indexOf(settings.speechRate).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    SpeechSettings.SPEECH_TONE_OPTIONS.getOrNull(index)?.let { store.edit(settings.copy(speechRate = it)) }
                },
            )
            EtaPreferenceDivider(hasLeading = false)
            EtaOverlayDropdownPreference(
                title = stringResource(R.string.speech_pitch),
                items = labels,
                selectedIndex = SpeechSettings.SPEECH_TONE_OPTIONS.indexOf(settings.speechPitch).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    SpeechSettings.SPEECH_TONE_OPTIONS.getOrNull(index)?.let { store.edit(settings.copy(speechPitch = it)) }
                },
            )
        }
        SpeechNote(stringResource(R.string.speech_tone_note))
    }
}

/** 试听使用当前草稿；播放中再次点击停止，错误直接显示在行摘要里。 */
@Composable
private fun SpeechPreviewSection(store: SpeechSettingsStore) {
    val playback by store.playback.state.collectAsState()
    val playing = playback.messageId != null

    Column {
        EtaPreferenceGroupTitle(stringResource(R.string.speech_preview_voice))
        EtaPreferenceGroup {
            EtaPreference(
                title = stringResource(
                    if (playing) R.string.speech_preview_stop else R.string.speech_preview_voice,
                ),
                summary = playback.error ?: if (playback.loading) {
                    stringResource(R.string.speech_preview_loading)
                } else {
                    null
                },
                summaryColor = if (playback.error != null) {
                    BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.error)
                } else {
                    BasicComponentDefaults.summaryColor()
                },
                enabled = !store.saving,
                onClick = {
                    if (playing) {
                        store.playback.stop()
                    } else {
                        store.playback.speak(
                            "preview",
                            "你好，我是 Eta。有什么可以帮你？",
                            store.settings,
                            store.credentials,
                        )
                    }
                },
            )
        }
        SpeechNote(stringResource(R.string.speech_preview_note))
    }
}
