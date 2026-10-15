package io.github.mangi.eta.agent.display

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.MAX_VIRTUAL_SCREEN_FAVORITES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class Task(
    val id: Int,
    val label: String,
    val icon: android.graphics.Bitmap?,
    val component: String = "",
    val packageName: String = "",
)

/**
 * A navigator for this display's existing tasks; it never opens the phone's launcher or global Recents.
 * APPS 模式额外列出手动可启动的应用（含收藏），仅在本 display 内启动。
 */
internal class VirtualScreenHomeActivity : ComponentActivity() {
    private var tasks by mutableStateOf<List<Task>>(emptyList())
    private var favorites by mutableStateOf<Set<String>>(emptySet())
    private var mode by mutableStateOf("HOME")
    private var failed by mutableStateOf(false)
    private var sessionId = ""
    /** 应用搜索关键词：只过滤当前列表，不影响收藏与启动。 */
    private var query by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!acceptIntent(intent)) { finish(); return }
        setContent {
            val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initial = AppearanceSettings())
            AgentAppTheme(appearance, applyInterfaceScale = true) {
                val state by VirtualScreenSession.state.collectAsState()
                androidx.compose.runtime.LaunchedEffect(state.display?.sessionId) {
                    val live = state.display?.sessionId
                    // 建屏瞬间会话状态可能还没落地：只有明确对不上才关闭，避免刚拉起就被自己关掉。
                    if (live != null && live != sessionId) finish()
                }
                Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).padding(top = 48.dp, start = 20.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(titleFor(mode), style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onBackground)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MODES.forEach { entry ->
                            Text(titleFor(entry),
                                modifier = Modifier
                                    .background(
                                        if (mode == entry) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainer,
                                        CircleShape,
                                    )
                                    .clickable { switchMode(entry) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    if (mode == "APPS" && tasks.isNotEmpty()) {
                        TextField(
                            value = query,
                            onValueChange = { value -> query = value },
                            label = stringResource(R.string.virtual_screen_search_hint),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.virtual_screen_favorite_hint),
                            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (tasks.isEmpty()) Text(
                        stringResource(if (mode == "APPS") R.string.virtual_screen_apps_empty else R.string.virtual_screen_home_empty),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (failed) Text(stringResource(R.string.virtual_screen_input_failed), color = MiuixTheme.colorScheme.error)
                    LazyVerticalGrid(columns = GridCells.Adaptive(if (mode == "MENU") 260.dp else 150.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (mode == "APPS") {
                            val keyword = query.trim()
                            val matched = if (keyword.isEmpty()) {
                                tasks
                            } else {
                                tasks.filter { task ->
                                    task.label.contains(keyword, ignoreCase = true) ||
                                        task.packageName.contains(keyword, ignoreCase = true)
                                }
                            }
                            if (matched.isEmpty() && keyword.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    SectionLabel(stringResource(R.string.virtual_screen_search_empty, keyword))
                                }
                            }
                            val pinned = matched.filter { it.packageName in favorites }
                            val rest = matched.filter { it.packageName !in favorites }
                            if (pinned.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.virtual_screen_favorites)) }
                                items(pinned, key = { "pin-" + it.component }) { task -> AppCard(task, true) }
                            }
                            if (rest.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.virtual_screen_all_apps)) }
                                items(rest, key = { "all-" + it.component }) { task -> AppCard(task, false) }
                            }
                        } else {
                            items(tasks, key = { "task-${it.id}-${it.label}" }) { task ->
                                EtaCard(onClick = { select(task) }, insideMargin = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        task.icon?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp)) }
                                        Text(task.label, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        loadTasks()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!acceptIntent(intent)) { finish(); return }
        loadTasks()
    }

    private fun acceptIntent(next: Intent): Boolean {
        val requested = next.getStringExtra("sessionId").orEmpty()
        if (requested.isEmpty()) return false
        val info = VirtualScreenSession.state.value.display
        // 会话状态尚未落地时先接受，等状态到位后再由 LaunchedEffect 校验；已落地就严格比对。
        if (info != null && (info.displayId != display?.displayId || requested != info.sessionId)) return false
        sessionId = requested
        mode = next.getStringExtra("mode") ?: "HOME"
        return true
    }

    private fun switchMode(next: String) {
        if (mode == next) return
        mode = next
        tasks = emptyList()
        loadTasks()
    }

    private fun loadTasks() {
        failed = false
        if (mode == "APPS") {
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) { launchableApps() }
                tasks = loaded
                favorites = withContext(Dispatchers.IO) { runCatching { SettingsDataStore.favoriteApps() }.getOrDefault(emptySet()) }
            }
            return
        }
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) {
                val result = JSONObject(VirtualScreenSession.tasksForViewer(this@VirtualScreenHomeActivity, sessionId).content)
                val array = result.optJSONArray("tasks") ?: return@withContext emptyList()
                (0 until array.length()).map { index ->
                    val task = array.getJSONObject(index)
                    val pkg = task.getString("packageName")
                    val app = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                    Task(task.getInt("taskId"), app?.let { packageManager.getApplicationLabel(it).toString() } ?: pkg,
                        app?.let { runCatching { packageManager.getApplicationIcon(it).toBitmap(64, 64) }.getOrNull() })
                }
            }
            tasks = next
        }
    }

    /** 已安装且可启动的应用；排除自己，避免在小屏里再套一层 Eta。 */
    private fun launchableApps(): List<Task> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return resolved
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .map { info ->
                Task(0, info.loadLabel(packageManager).toString(),
                    runCatching { info.loadIcon(packageManager).toBitmap(64, 64) }.getOrNull(),
                    info.activityInfo.packageName + "/" + info.activityInfo.name,
                    info.activityInfo.packageName)
            }
            .sortedBy { it.label }
    }

    private fun toggleFavorite(task: Task) {
        val current = favorites
        val next = when {
            task.packageName in current -> current - task.packageName
            current.size >= MAX_VIRTUAL_SCREEN_FAVORITES -> {
                Toast.makeText(this, getString(R.string.virtual_screen_favorite_limit, MAX_VIRTUAL_SCREEN_FAVORITES), Toast.LENGTH_SHORT).show()
                return
            }
            else -> current + task.packageName
        }
        favorites = next
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { SettingsDataStore.setFavoriteApps(next) }
        }
    }

    private fun select(task: Task) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (mode == "APPS") VirtualScreenSession.launchForViewer(this@VirtualScreenHomeActivity, sessionId, task.component)
                else VirtualScreenSession.inputForViewer(this@VirtualScreenHomeActivity, sessionId,
                    JSONObject().put("action", "switch_task").put("taskId", task.id))
            }
            failed = !JSONObject(result.content).optBoolean("ok")
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(text, modifier = Modifier.padding(top = 4.dp), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }

    /** 星标只切换收藏，不启动应用；收藏是最多 3 个，和顶栏直通车对应。 */
    @Composable
    private fun AppCard(task: Task, favorite: Boolean) {
        EtaCard(onClick = { select(task) }, insideMargin = PaddingValues(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                task.icon?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(28.dp)) }
                Text(task.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                Text(if (favorite) "★" else "☆",
                    modifier = Modifier.clickable { toggleFavorite(task) }.padding(6.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = if (favorite) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }

    @Composable
    private fun titleFor(entry: String): String = stringResource(
        when (entry) {
            "MENU" -> R.string.virtual_screen_menu
            "APPS" -> R.string.virtual_screen_apps
            else -> R.string.virtual_screen_home
        }
    )

    private companion object {
        val MODES = listOf("HOME", "MENU", "APPS")
    }
}
