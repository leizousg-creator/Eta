package io.github.mangi.eta.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.VirtualScreenProfile
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.window.WindowListPopup
import kotlin.math.roundToInt

/**
 * 顶栏“虚拟屏”胶囊：点一下弹出最多 4 项 —— 打开虚拟屏（应用菜单）+ 3 个收藏应用直通车。
 * 全程走 display 隔离路由，不会碰主屏任务。
 */
@Composable
internal fun VirtualScreenTopBarCapsule() {
    val context = LocalContext.current
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = Settings())
    if (!settings.virtualScreenEnabled) return
    val favoritePackages by SettingsDataStore.favoriteAppsFlow().collectAsState(initial = emptySet())
    val shortcuts = remember(favoritePackages) {
        favoritePackages.sorted().take(MAX_VIRTUAL_SCREEN_FAVORITES).mapNotNull { appShortcut(context, it) }
    }
    val session by VirtualScreenSession.state.collectAsState()
    val running = session.display != null
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** 先拉起查看器（立刻有界面反馈），会话创建与目标应用启动放到后台。 */
    fun open(component: String?) {
        showMenu = false
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
            if (!opened) Toast.makeText(context, context.getString(R.string.virtual_screen_manual_failed), Toast.LENGTH_LONG).show()
        }
    }

    Box {
        StatusCapsule(
            text = stringResource(if (running) R.string.virtual_screen_manual_view else R.string.virtual_screen_manual_open),
            modifier = Modifier.clickable { showMenu = true },
            description = stringResource(R.string.virtual_screen_manual_description),
        )
        WindowListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            val items = mutableListOf(DropdownItem(text = stringResource(R.string.virtual_screen_open_menu)))
            shortcuts.forEach { shortcut ->
                items += DropdownItem(
                    text = shortcut.label,
                    icon = { modifier -> shortcut.icon?.let { ShortcutIcon(it, modifier) } },
                )
            }
            ListPopupColumn {
                items.forEachIndexed { index, item ->
                    DropdownImpl(
                        item = item,
                        optionSize = items.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            if (index == 0) open(null) else shortcuts.getOrNull(index - 1)?.let { open(it.component) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutIcon(bitmap: android.graphics.Bitmap, modifier: Modifier) {
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
