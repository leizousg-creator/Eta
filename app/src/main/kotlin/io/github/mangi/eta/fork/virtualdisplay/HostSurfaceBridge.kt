package io.github.mangi.eta.fork.virtualdisplay

import android.view.Surface
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySessionResources
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySurfaceRegistry
import org.json.JSONObject

/**
 * 直投（host surface）桥接。
 *
 * Eta fork 专有：平时的路径是 ImageReader → 解码 Bitmap → Compose 重绘，每帧多两三次拷贝；
 * 直投时把 VirtualDisplay 的 surface 换成 UI 里 SurfaceView 的 surface，由 SurfaceFlinger
 * 直接合成，延迟接近原生。用不了就返回 false，调用方继续走 ImageReader。
 *
 * 这里刻意做成独立对象，不写进 VirtualDisplayController：上游重构 session 层时，
 * Controller 只需要保留三行转发，直投逻辑不受影响。
 */
internal object HostSurfaceBridge {
    @Volatile
    private var attachedSurface: Surface? = null

    val isAttached: Boolean
        get() = attachedSurface != null

    /** 把画面切到 UI 的 surface。 */
    fun attach(resources: VirtualDisplaySessionResources?, surface: Surface): Boolean {
        if (resources == null || !surface.isValid) return false
        if (!sendSurface(resources, surface)) return false
        attachedSurface = surface
        return true
    }

    /** 从直投切回 ImageReader（截图、后台观察都依赖它）。 */
    fun detach(resources: VirtualDisplaySessionResources?): Boolean {
        if (attachedSurface == null) return false
        val reader = resources?.reader?.surface ?: return false
        if (!sendSurface(resources, reader)) return false
        attachedSurface = null
        return true
    }

    /** 截图 / 观察前确保 VirtualDisplay 接的是 ImageReader，否则拿不到帧。 */
    fun ensureReaderSurface(resources: VirtualDisplaySessionResources?) {
        if (attachedSurface == null) return
        val reader = resources?.reader?.surface ?: return
        if (sendSurface(resources, reader)) attachedSurface = null
    }

    private fun sendSurface(resources: VirtualDisplaySessionResources, surface: Surface): Boolean {
        val bridge = resources.bridge
        if (bridge != null) {
            val token = VirtualDisplaySurfaceRegistry.register(surface)
            val payload = JSONObject()
                .put("action", "set_surface")
                .put("surface_token", token)
                .toString() + "\n"
            val ok = runCatching {
                bridge.process.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                bridge.process.outputStream.flush()
                true
            }.getOrDefault(false)
            if (!ok) VirtualDisplaySurfaceRegistry.unregister(token)
            return ok
        }
        val display = resources.virtualDisplay ?: return false
        return runCatching { display.surface = surface }.isSuccess
    }
}
