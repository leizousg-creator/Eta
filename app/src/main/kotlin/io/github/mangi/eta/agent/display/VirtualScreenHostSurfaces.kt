package io.github.mangi.eta.agent.display

import android.view.Surface
import java.util.UUID

/**
 * 直通出帧（A′）：把本机 Surface 交给 root helper，当虚拟屏的输出面。
 * 画面由 SurfaceFlinger 直接合成进本 Surface，不再走截屏→JPEG→base64→解码。
 */
internal object VirtualScreenHostSurfaces {
    const val AUTHORITY = "io.github.mangi.xiaoaiwindow.hostsurface"

    val token: String = UUID.randomUUID().toString()

    private val slots = LinkedHashMap<String, Surface>()

    @Volatile
    var activeHost: String? = null
        private set

    /** 最近一次真正下发过的 (host, surface) 指纹，避免重复下发。 */
    @Volatile
    private var sentKey: String? = null

    /** 活动 host 变化（含置空）时回调，由持有会话的一侧去下发指令。 */
    @Volatile
    var onActiveChanged: (() -> Unit)? = null

    /** helper 是否已用宿主面（直通）建屏：create 响应 direct=true 才允许界面切直通。 */
    @Volatile
    var live: Boolean = false

    fun attach(host: String, surface: Surface?) {
        synchronized(slots) {
            if (surface == null) {
                slots.remove(host)
                if (activeHost == host) activeHost = slots.keys.lastOrNull()
            } else {
                slots[host] = surface
                activeHost = host
            }
        }
        onActiveChanged?.invoke()
    }

    fun currentHost(): String? = synchronized(slots) { activeHost }

    fun currentSurface(): Surface? = synchronized(slots) { activeHost?.let { slots[it] } }

    /** (host, surface 代次) 指纹：同一次交接不重复下发。 */
    fun key(): String = "$token:${currentHost().orEmpty()}:${currentSurface()?.hashCode() ?: 0}"

    fun markSent(value: String) {
        sentKey = value
    }

    fun alreadySent(): Boolean = sentKey == key()

    fun resetSent() {
        sentKey = null
    }
}
