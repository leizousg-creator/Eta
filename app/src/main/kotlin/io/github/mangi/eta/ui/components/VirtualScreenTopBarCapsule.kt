package io.github.mangi.eta.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.drawable.toBitmap
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.VirtualScreenProfile
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 顶栏溢出菜单里的「虚拟屏」条目：打开虚拟屏，或直达某个收藏应用。
 * 全程走 display 隔离路由，不会碰主屏任务。
 */
internal data class VirtualScreenMenuEntry(
    val label: String,
    val component: String?,
    val icon: android.graphics.Bitmap?,
)

/** 收集收藏应用并生成菜单条目；首项固定是「打开虚拟屏」。 */
@Composable
internal fun rememberVirtualScreenMenuEntries(): List<VirtualScreenMenuEntry> {
    val context = LocalContext.current
    val favoritePackages by SettingsDataStore.favoriteAppsFlow().collectAsState(initial = emptySet())
    return remember(favoritePackages, context) {
        val entries = mutableListOf(
            VirtualScreenMenuEntry(
                label = context.getString(R.string.virtual_screen_open_menu),
                component = null,
                icon = null,
            ),
        )
        favoritePackages.sorted().take(MAX_VIRTUAL_SCREEN_FAVORITES).forEach { packageName ->
            appShortcut(context, packageName)?.let { shortcut ->
                entries += VirtualScreenMenuEntry(shortcut.label, shortcut.component, shortcut.icon)
            }
        }
        entries
    }
}

/** 先拉起查看器（立刻有界面反馈），会话创建与目标应用启动放到后台。 */
internal fun openVirtualScreen(
    context: Context,
    scope: CoroutineScope,
    component: String?,
) {
    runCatching { context.startActivity(Intent(context, VirtualScreenViewerActivity::class.java)) }
    scope.launch {
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                var created = false
                if (!VirtualScreenSession.isActive()) {
                    val base = VirtualScreenProfile.from(context)
                    // 手动会话默认降一档分辨率/密度：编解码开销随像素下降，滑动明显更跟手。
                    val result = JSONObject(
                        VirtualScreenSession.execute(
                            context, MANUAL_OWNER, JSONObject()
                                .put("action", "create").put("width", (base.width * MANUAL_SCALE).roundToInt())
                                .put("height", (base.height * MANUAL_SCALE).roundToInt())
                                .put("density", (base.density * MANUAL_SCALE).roundToInt())
                        ).content
                    )
                    require(result.optBoolean("ok")) { result.optString("code") }
                    created = true
                }
                val info = VirtualScreenSession.state.value.display ?: error("NO_VIRTUAL_SCREEN")
                if (!component.isNullOrBlank()) {
                    val launched = JSONObject(VirtualScreenSession.launchForViewer(context, info.sessionId, component).content)
                    require(launched.optBoolean("ok")) { launched.optString("code") }
                } else if (created) {
                    VirtualScreenSession.homeForViewer(context, info.sessionId, "APPS")
                }
                true
            }.getOrDefault(false)
        }
        if (!opened) {
            Toast.makeText(context, context.getString(R.string.virtual_screen_manual_failed), Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
internal fun ShortcutIcon(bitmap: android.graphics.Bitmap, modifier: Modifier) {
    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = modifier)
}

/** 手动会话的 owner；与 Agent run 的 owner 区分，互不抢锁。 */
internal const val MANUAL_OWNER = "manual"

/** 应用菜单与顶栏直通车的收藏上限。 */
internal const val MAX_VIRTUAL_SCREEN_FAVORITES = 3

/** 手动会话分辨率/密度缩放：等比缩小以降低每帧编解码开销。 */
private const val MANUAL_SCALE = 0.72f

private data class AppShortcut(
    val label: String,
    val component: String,
    val icon: android.graphics.Bitmap?,
)

/** 取包名对应的启动入口、显示名与图标；没有可启动入口就跳过。 */
private fun appShortcut(context: Context, packageName: String): AppShortcut? {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
    val resolved = runCatching { context.packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
    val info = resolved.firstOrNull() ?: return null
    val label = runCatching {
        info.loadLabel(context.packageManager).toString().take(8).ifBlank { packageName }
    }.getOrDefault(packageName)
    val icon = runCatching {
        info.loadIcon(context.packageManager)
            .toBitmap(48, 48)
    }.getOrNull()
    return AppShortcut(label, info.activityInfo.packageName + "/" + info.activityInfo.name, icon)
}
