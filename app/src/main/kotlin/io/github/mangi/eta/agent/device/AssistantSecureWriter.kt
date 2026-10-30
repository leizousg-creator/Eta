package io.github.mangi.eta.agent.device

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 把 Eta 设为系统默认数字助理。
 *
 * vivo（OriginOS）的 RoleManager 不接受第三方申请，`Settings.ACTION_VOICE_INPUT_SETTINGS`
 * 也是空列表，所以走系统正规入口走不通。这里用 root 直接改写两个 secure 键：
 *
 *  - `assistant`
 *  - `voice_interaction_service`
 *
 * 写入后 `dumpsys voiceinteraction` 的 `mComponent` 会立刻指向 Eta 的
 * `EtaVoiceInteractionService`，无需重启。
 */
internal object AssistantSecureWriter {
    private const val TAG = "EtaAssistant"
    private const val TIMEOUT_SECONDS = 10L

    private fun componentFor(context: Context): String =
        "${context.packageName}/${context.packageName}.agent.voice.EtaVoiceInteractionService"

    /** 当前生效的 secure assistant 值（只读，不需要 root）。 */
    fun currentAssistant(context: Context): String? = runCatching {
        android.provider.Settings.Secure.getString(context.contentResolver, "assistant")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 用 root 写入两个 secure 键；成功后返回 true。 */
    fun apply(context: Context): Boolean {
        if (!RootAccess.isGranted) {
            Log.i(TAG, "apply: root 未授权，跳过")
            return false
        }
        val component = componentFor(context)
        val command = buildString {
            append("settings put secure assistant '").append(component).append("'; ")
            append("settings put secure voice_interaction_service '").append(component).append("'")
        }
        val ok = runRoot(command)
        Log.i(TAG, "apply: component=$component ok=$ok")
        return ok
    }

    /** 清空，恢复成“没有默认数字助理”。 */
    fun clear(): Boolean = runRoot(
        "settings delete secure assistant; settings delete secure voice_interaction_service",
    )

    private fun runRoot(command: String): Boolean {
        val process = runCatching {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrNull() ?: return false
        return runCatching {
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }
}
