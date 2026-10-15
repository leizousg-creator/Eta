package io.github.mangi.eta.ui.screens.selfcheck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.core.DiagnosticsPatrol
import io.github.mangi.eta.core.DiagnosticsPatrolAlarm
import io.github.mangi.eta.core.DiagnosticsPatrolInterval
import io.github.mangi.eta.core.DiagnosticsPatrolRules
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 关于 → 极致自动排查：定时在本机把「可能出事的地方」逐项查一遍。
 *
 * 这里只做界面：开关、周期、保留份数、提醒口径、立即巡检、报告列表。
 * 真正的巡检逻辑在 [DiagnosticsPatrol]，定时靠 [DiagnosticsPatrolAlarm] 排一次闹钟。
 */
@Composable
internal fun DiagnosticsPatrolScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = Settings())
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var intervalInput by remember { mutableStateOf("") }
    var showKeepDialog by remember { mutableStateOf(false) }
    var keepInput by remember { mutableStateOf("") }
    var reports by remember { mutableStateOf<List<File>>(emptyList()) }
    var round by remember { mutableStateOf(0) }
    var viewingName by remember { mutableStateOf<String?>(null) }
    var viewingText by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf<List<DiagnosticsPatrolRules.Rule>>(emptyList()) }
    var showRules by remember { mutableStateOf(false) }

    LaunchedEffect(round) {
        reports = withContext(Dispatchers.IO) { DiagnosticsPatrol.reports(limit = 10) }
        rules = withContext(Dispatchers.IO) { DiagnosticsPatrolRules.load() }
    }

    val lastSummary = when {
        note != null -> note.orEmpty()
        settings.diagPatrolLastAt > 0L -> stringResource(
            R.string.diag_patrol_last,
            formatTime(settings.diagPatrolLastAt) + "｜" + settings.diagPatrolLastSummary,
        )
        else -> stringResource(R.string.diag_patrol_never)
    }

    MiuixScaffoldPage(title = stringResource(R.string.diag_patrol_title), onBack = onBack) {
        item(key = "diag-patrol-head") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(R.string.diag_patrol_head_title),
                    summary = stringResource(R.string.diag_patrol_head_summary),
                )
            }
        }

        item(key = "diag-patrol-auto") {
            EtaPreferenceGroupTitle(stringResource(R.string.diag_patrol_section_auto))
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = stringResource(R.string.diag_patrol_enable),
                    summary = stringResource(R.string.diag_patrol_enable_summary),
                    checked = settings.diagPatrolEnabled,
                    onCheckedChange = { value ->
                        note = null
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(diagPatrolEnabled = value) }
                            if (value) DiagnosticsPatrolAlarm.refresh(context) else DiagnosticsPatrolAlarm.cancel(context)
                        }
                    },
                )

                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.diag_patrol_interval),
                    summary = DiagnosticsPatrolInterval.label(settings.diagPatrolIntervalMinutes),
                    enabled = settings.diagPatrolEnabled,
                    onClick = {
                        intervalInput = settings.diagPatrolIntervalMinutes.toString()
                        showIntervalDialog = true
                    },
                )

                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.diag_patrol_keep),
                    summary = stringResource(R.string.diag_patrol_keep_value, settings.diagPatrolKeepCount),
                    enabled = settings.diagPatrolEnabled,
                    onClick = {
                        keepInput = settings.diagPatrolKeepCount.toString()
                        showKeepDialog = true
                    },
                )

                EtaPreferenceDivider(hasLeading = true)
                EtaSwitchPreference(
                    title = stringResource(R.string.diag_patrol_only_abnormal),
                    summary = stringResource(R.string.diag_patrol_only_abnormal_summary),
                    checked = settings.diagPatrolNotifyOnlyAbnormal,
                    onCheckedChange = { value ->
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(diagPatrolNotifyOnlyAbnormal = value) }
                        }
                    },
                )
            }
        }

        item(key = "diag-patrol-custom") {
            EtaPreferenceGroupTitle(stringResource(R.string.diag_patrol_section_custom))
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = stringResource(R.string.diag_patrol_custom_entry),
                    summary = stringResource(R.string.diag_patrol_custom_entry_summary, rules.size),
                    onClick = { showRules = true },
                )
            }
        }

        item(key = "diag-patrol-run") {
            EtaPreferenceGroupTitle(stringResource(R.string.diag_patrol_section_run))
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = stringResource(R.string.diag_patrol_run_now),
                    summary = lastSummary,
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            note = context.getString(R.string.diag_patrol_running)
                            val result = runCatching {
                                DiagnosticsPatrol.runOnce(context, reason = "手动巡检", exportBundle = false)
                            }
                            busy = false
                            note = result.fold(
                                onSuccess = { context.getString(R.string.diag_patrol_done, it.oneLine()) },
                                onFailure = {
                                    context.getString(R.string.diag_patrol_failed, it.message.orEmpty())
                                },
                            )
                            Toast.makeText(context, note.orEmpty(), Toast.LENGTH_SHORT).show()
                            round += 1
                        }
                    },
                )
            }
        }

        item(key = "diag-patrol-reports") {
            EtaPreferenceGroupTitle(stringResource(R.string.diag_patrol_section_reports))
            EtaPreferenceGroup {
                val files = reports
                if (files.isEmpty()) {
                    EtaPreference(
                        title = stringResource(R.string.diag_patrol_no_reports),
                        summary = DiagnosticsPatrol.BASE_DIR,
                    )
                } else {
                    files.forEachIndexed { index, file ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = true)
                        EtaArrowPreference(
                            title = file.name,
                            summary = formatTime(file.lastModified()) + "｜" + sizeText(file),
                            onClick = {
                                scope.launch {
                                    val text = withContext(Dispatchers.IO) {
                                        runCatching { file.readText() }.getOrDefault("")
                                    }
                                    if (text.isBlank()) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.diag_patrol_failed, file.name),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        viewingName = file.name
                                        viewingText = text
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showRules) {
        DiagnosticsPatrolRulesDialog(
            rules = rules,
            onDismiss = { showRules = false },
            onRulesChange = { updated ->
                rules = updated
                scope.launch(Dispatchers.IO) { DiagnosticsPatrolRules.save(updated) }
            },
        )
    }

    if (showIntervalDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.diag_patrol_interval_dialog_title),
            summary = stringResource(
                R.string.diag_patrol_interval_dialog_summary,
                DiagnosticsPatrolInterval.MIN_MINUTES,
                DiagnosticsPatrolInterval.MAX_MINUTES,
            ),
            onDismissRequest = { showIntervalDialog = false },
        ) {
            Column {
                TextField(
                    value = intervalInput,
                    onValueChange = { intervalInput = it },
                    label = stringResource(R.string.diag_patrol_interval_dialog_label),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_confirm),
                    onCancel = { showIntervalDialog = false },
                    onConfirm = {
                        val minutes = intervalInput.trim().toIntOrNull()
                        showIntervalDialog = false
                        if (minutes == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.diag_patrol_number_invalid),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val value = DiagnosticsPatrolInterval.normalize(minutes)
                            scope.launch(Dispatchers.IO) {
                                SettingsDataStore.updateSettings { it.copy(diagPatrolIntervalMinutes = value) }
                                DiagnosticsPatrolAlarm.refresh(context)
                            }
                        }
                    },
                )
            }
        }
    }

    if (showKeepDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.diag_patrol_keep_dialog_title),
            summary = stringResource(
                R.string.diag_patrol_keep_dialog_summary,
                DIAG_PATROL_KEEP_MIN,
                DIAG_PATROL_KEEP_MAX,
            ),
            onDismissRequest = { showKeepDialog = false },
        ) {
            Column {
                TextField(
                    value = keepInput,
                    onValueChange = { keepInput = it },
                    label = stringResource(R.string.diag_patrol_keep_dialog_label),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_confirm),
                    onCancel = { showKeepDialog = false },
                    onConfirm = {
                        val count = keepInput.trim().toIntOrNull()
                        showKeepDialog = false
                        if (count == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.diag_patrol_number_invalid),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val value = count.coerceIn(DIAG_PATROL_KEEP_MIN, DIAG_PATROL_KEEP_MAX)
                            scope.launch(Dispatchers.IO) {
                                SettingsDataStore.updateSettings { it.copy(diagPatrolKeepCount = value) }
                            }
                        }
                    },
                )
            }
        }
    }

    val name = viewingName
    if (name != null) {
        EtaWindowDialog(
            show = true,
            title = name,
            summary = stringResource(R.string.diag_patrol_view_hint, viewingText.length),
            onDismissRequest = { viewingName = null },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = viewingText,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.diag_patrol_copy_all),
                onCancel = { viewingName = null },
                onConfirm = {
                    copyText(context, viewingText)
                    Toast.makeText(
                        context,
                        context.getString(R.string.diag_patrol_copied_all, viewingText.length),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }
}

private const val DIAG_PATROL_KEEP_MIN = 1
private const val DIAG_PATROL_KEEP_MAX = 60

private fun copyText(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    runCatching { manager.setPrimaryClip(ClipData.newPlainText("Eta 巡检报告", text)) }
}

private fun formatTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

private fun sizeText(file: File): String {
    val bytes = file.length()
    return when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
