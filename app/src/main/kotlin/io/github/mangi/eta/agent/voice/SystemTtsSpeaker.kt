package io.github.mangi.eta.agent.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 手机自带语音（Android TextToSpeech）：不联网、不填密钥，用于零配置的朗读入口。
 * 引擎惰性初始化并常驻一个实例；宿主停止朗读时调用 [stop]，页面销毁时调用 [release]。
 */
@Suppress("DEPRECATION")
internal object SystemTtsSpeaker {
    @Volatile
    private var instance: TextToSpeech? = null

    fun stop() {
        instance?.stop()
    }

    fun release() {
        instance?.let {
            it.stop()
            it.shutdown()
        }
        instance = null
    }

    suspend fun speak(context: Context, text: String, rate: Float = 1f, pitch: Float = 1f) {
        val tts = withContext(Dispatchers.Main.immediate) { engine(context) }
        withContext(Dispatchers.Main.immediate) {
            // 引擎常驻，语速与音调每次朗读前重设，跟着设置走。
            tts.setSpeechRate(rate.coerceIn(MIN_TONE, MAX_TONE))
            tts.setPitch(pitch.coerceIn(MIN_TONE, MAX_TONE))
            suspendCancellableCoroutine<Unit> { continuation ->
                val utteranceId = "eta-" + System.nanoTime()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit

                    override fun onDone(id: String?) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(id: String?) = fail()

                    override fun onError(id: String?, errorCode: Int) = fail()

                    private fun fail() {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                SpeechFailure(SpeechErrorCode.AUDIO, "系统语音播报失败"),
                            )
                        }
                    }
                })
                continuation.invokeOnCancellation { tts.stop() }
                val started = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
                if (started == TextToSpeech.ERROR && continuation.isActive) {
                    continuation.resumeWithException(
                        SpeechFailure(SpeechErrorCode.AUDIO, "系统语音播报失败"),
                    )
                }
            }
        }
    }

    private suspend fun engine(context: Context): TextToSpeech {
        instance?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context.applicationContext) { status ->
                val tts = created
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.language = Locale.getDefault()
                    instance = tts
                    if (continuation.isActive) continuation.resume(tts)
                } else {
                    tts?.shutdown()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            SpeechFailure(SpeechErrorCode.CONFIGURATION, "手机没有可用的语音引擎"),
                        )
                    }
                }
            }
            continuation.invokeOnCancellation { created?.shutdown() }
        }
    }

    private const val MIN_TONE = 0.25f
    private const val MAX_TONE = 3f
}
