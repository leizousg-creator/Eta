package io.github.mangi.eta.agent.voice

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import io.github.mangi.eta.core.AssistantContextFlags

class EtaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        activeService = this
        if (Build.VERSION.SDK_INT >= 37) {
            setInvocationEffectEnabled(true)
        }
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(
            Intent(this, EtaVoiceAssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun showEtaSession() {
        showSession(Bundle(), AssistantContextFlags.forSdk(Build.VERSION.SDK_INT))
    }

    companion object {
        @Volatile
        private var activeService: EtaVoiceInteractionService? = null

        internal fun requestSession(): Boolean {
            val service = activeService ?: return false
            service.showEtaSession()
            return true
        }
    }
}

class EtaVoiceAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 助理服务没在跑时（Eta 未设为默认数字助理，或 :voice 进程刚被回收），
        // showSession 无从触发，此时必须直拉主界面，否则外部唤醒入口
        // （白条上滑 / 侧键 / 圈搜）会表现为"按了没反应"。
        if (!EtaVoiceInteractionService.requestSession()) {
            runCatching {
                startActivity(
                    Intent().setClassName(packageName, MAIN_ACTIVITY).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                )
            }
        }
        finish()
    }

    private companion object {
        const val MAIN_ACTIVITY = "io.github.mangi.eta.ui.MainActivity"
    }
}
