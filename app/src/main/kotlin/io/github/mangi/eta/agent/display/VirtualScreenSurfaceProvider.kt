package io.github.mangi.eta.agent.display

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor

/**
 * 只对 root helper 开放的取面通道：helper 通过 ActivityManager 外部取到本 provider 后
 * call("surface", …) 拿回当前活动 Surface（Bundle 里带 Surface，走 binder，跨进程可用）。
 * provider 带 signature 级权限，普通应用调不到；root（uid 0）放行。
 */
class VirtualScreenSurfaceProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SURFACE) return null
        val token = extras?.getString(EXTRA_TOKEN) ?: return null
        if (token != VirtualScreenHostSurfaces.token) return null
        val surface = VirtualScreenHostSurfaces.currentSurface() ?: return null
        return Bundle().apply {
            putString(EXTRA_HOST, VirtualScreenHostSurfaces.currentHost().orEmpty())
            putParcelable(EXTRA_SURFACE, surface)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null

    companion object {
        const val METHOD_SURFACE = "surface"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_SURFACE = "surface"
        const val EXTRA_HOST = "host"
    }
}
