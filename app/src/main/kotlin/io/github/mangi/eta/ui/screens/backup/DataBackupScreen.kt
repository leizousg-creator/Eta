package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.agent.automation.AutoBackupAlarm
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AutoBackupInterval
import io.github.mangi.eta.data.model.AutoBackupKeepCount
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.repository.AutoBackupResult
import io.github.mangi.eta.data.repository.AutoBackupRunner
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream) -> EtaBackupSummary,
    onImport: suspend (InputStream) -> EtaBackupSummary,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = Settings())
    var autoBusy by remember { mutableStateOf(false) }
    var autoNote by remember { mutableStateOf<String?>(null) }
    var showDirDialog by remember { mutableStateOf(false) }
    var dirInput by remember { mutableStateOf("") }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var intervalInput by remember { mutableStateOf("") }
    var showKeepDialog by remember { mutableStateOf(false) }
    var keepInput by remember { mutableStateOf("") }

    fun showFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        AndroidAgentLogger.error("Agent backup UI failed: type=${throwable.safeLogType()}\n${throwable.safeStackTrace()}")
        Toast.makeText(
            context,
            throwable.message ?: context.getString(R.string.data_backup_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                val summary = output.use { onExport(it) }
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.data_backup_exported,
                        summary.conversationCount,
                        summary.providerCount,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.data_backup_title),
        onBack = onBack,
    ) {
        item(key = "warning") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaPreference(
                    title = stringResource(R.string.data_backup_warning_title),
                    summary = stringResource(R.string.data_backup_warning_summary),
                )
            }
        }
        item(key = "actions-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.data_backup_actions))
        }
        item(key = "actions-card") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_export),
                    summary = if (busy) {
                        stringResource(R.string.data_backup_working)
                    } else {
                        stringResource(R.string.data_backup_export_summary)
                    },
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Download,
                            loading = busy,
                        )
                    },
                    onClick = {
                        exportLauncher.launch(defaultBackupFileName())
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_import),
                    summary = stringResource(R.string.data_backup_import_summary),
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Description,
                            loading = false,
                        )
                    },
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                )
            }
        }
        item(key = "auto-backup-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.data_backup_auto))
        }
        item(key = "auto-backup-card") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                EtaSwitchPreference(
                    title = stringResource(R.string.data_backup_auto_enable),
                    summary = stringResource(R.string.data_backup_auto_enable_summary),
                    checked = settings.autoBackupEnabled,
                    onCheckedChange = { value ->
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(autoBackupEnabled = value) }
                            if (value) AutoBackupAlarm.refresh(context) else AutoBackupAlarm.cancel(context)
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_auto_interval),
                    summary = intervalLabel(context, settings.autoBackupIntervalMinutes),
                    enabled = settings.autoBackupEnabled,
                    onClick = {
                        intervalInput = AutoBackupInterval
                            .effectiveMinutes(settings.autoBackupIntervalMinutes, settings.autoBackupIntervalHours)
                            .toString()
                        showIntervalDialog = true
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_auto_keep),
                    summary = stringResource(R.string.data_backup_auto_keep_value, settings.autoBackupKeepCount),
                    enabled = settings.autoBackupEnabled,
                    onClick = {
                        keepInput = settings.autoBackupKeepCount.toString()
                        showKeepDialog = true
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_auto_run_now),
                    summary = autoNote ?: if (settings.autoBackupLastFileName.isNotBlank()) {
                        stringResource(R.string.data_backup_auto_last_file, settings.autoBackupLastFileName)
                    } else {
                        stringResource(R.string.data_backup_auto_never)
                    },
                    enabled = !autoBusy && !busy,
                    startAction = {
                        BackupIcon(icon = Icons.Rounded.Download, loading = autoBusy)
                    },
                    onClick = {
                        if (!autoBusy) {
                            autoBusy = true
                            scope.launch {
                                val result = AutoBackupRunner.maybeRun(context, force = true)
                                val text = when (result) {
                                    is AutoBackupResult.Done -> context.getString(
                                        R.string.data_backup_auto_done,
                                        result.fileName,
                                        result.conversationCount,
                                        result.kept,
                                    )
                                    is AutoBackupResult.Failed -> context.getString(
                                        R.string.data_backup_auto_failed,
                                        result.message,
                                    )
                                    is AutoBackupResult.Skipped -> context.getString(R.string.data_backup_auto_skipped)
                                }
                                AutoBackupAlarm.refresh(context)
                                autoBusy = false
                                autoNote = text
                                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_auto_dir),
                    summary = AutoBackupRunner.directory(settings.autoBackupDirectory).absolutePath + " · " +
                        stringResource(
                            if (settings.autoBackupDirectory.isBlank()) {
                                R.string.data_backup_dir_default
                            } else {
                                R.string.data_backup_dir_custom
                            },
                        ),
                    onClick = {
                        dirInput = settings.autoBackupDirectory
                        showDirDialog = true
                    },
                )
            }
        }
    }

    if (showDirDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_dir_dialog_title),
            summary = stringResource(
                R.string.data_backup_dir_dialog_summary,
                AutoBackupRunner.defaultDirectory().absolutePath,
            ),
            onDismissRequest = { showDirDialog = false },
        ) {
            Column {
                TextField(
                    value = dirInput,
                    onValueChange = { dirInput = it },
                    label = stringResource(R.string.data_backup_dir_dialog_label),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_confirm),
                    onCancel = { showDirDialog = false },
                    onConfirm = {
                        val value = dirInput.trim()
                        showDirDialog = false
                        scope.launch {
                            SettingsDataStore.updateSettings { it.copy(autoBackupDirectory = value) }
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.data_backup_dir_saved,
                                    AutoBackupRunner.directory(value).absolutePath,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }

    if (showIntervalDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_interval_dialog_title),
            summary = stringResource(
                R.string.data_backup_interval_dialog_summary,
                AutoBackupInterval.MIN_MINUTES,
                AutoBackupInterval.MAX_MINUTES,
            ),
            onDismissRequest = { showIntervalDialog = false },
        ) {
            Column {
                TextField(
                    value = intervalInput,
                    onValueChange = { intervalInput = it },
                    label = stringResource(R.string.data_backup_interval_dialog_label),
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
                                context.getString(R.string.data_backup_interval_invalid),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val value = AutoBackupInterval.normalize(minutes)
                            scope.launch {
                                SettingsDataStore.updateSettings { it.copy(autoBackupIntervalMinutes = value) }
                                AutoBackupAlarm.refresh(context)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.data_backup_interval_saved, minutesText(context, value)),
                                    Toast.LENGTH_SHORT,
                                ).show()
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
            title = stringResource(R.string.data_backup_keep_dialog_title),
            summary = stringResource(
                R.string.data_backup_keep_dialog_summary,
                AutoBackupKeepCount.MIN,
                AutoBackupKeepCount.MAX,
            ),
            onDismissRequest = { showKeepDialog = false },
        ) {
            Column {
                TextField(
                    value = keepInput,
                    onValueChange = { keepInput = it },
                    label = stringResource(R.string.data_backup_keep_dialog_label),
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
                                context.getString(R.string.data_backup_keep_invalid),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val value = AutoBackupKeepCount.normalize(count)
                            scope.launch {
                                SettingsDataStore.updateSettings { it.copy(autoBackupKeepCount = value) }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.data_backup_keep_saved, value),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
        }
    }

    if (showImportDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_import_confirm_title),
            summary = stringResource(R.string.data_backup_import_confirm_summary),
            onDismissRequest = {
                if (!busy) {
                    showImportDialog = false
                    pendingImportUri = null
                }
            },
        ) {
            MiuixDialogActions(
                confirmText = if (busy) {
                    stringResource(R.string.data_backup_working)
                } else {
                    stringResource(R.string.action_import)
                },
                destructive = true,
                cancelEnabled = !busy,
                confirmEnabled = !busy,
                onCancel = {
                    showImportDialog = false
                    pendingImportUri = null
                },
                onConfirm = {
                    val uri = pendingImportUri ?: return@MiuixDialogActions
                    showImportDialog = false
                    scope.launch {
                        busy = true
                        try {
                            val input = context.contentResolver.openInputStream(uri)
                                ?: error(context.getString(R.string.data_backup_file_open_failed))
                            val summary = input.use { onImport(it) }
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.data_backup_imported,
                                    summary.conversationCount,
                                    summary.providerCount,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (throwable: Throwable) {
                            showFailure(throwable)
                        } finally {
                            pendingImportUri = null
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun BackupIcon(icon: ImageVector, loading: Boolean) {
    Box(
        // 与 EtaPreferenceIcon 同宽，正文和分割线起点保持对齐。
        modifier = Modifier.size(EtaPreferenceDefaults.IconSize),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            InfiniteProgressIndicator(size = 20.dp)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}

private fun intervalLabel(context: Context, minutes: Int): String =
    context.getString(R.string.data_backup_auto_interval_value, minutesText(context, minutes))

/** 把分钟数写成「10 分钟 / 2 小时 / 1 天 / 1 小时 30 分钟」。 */
private fun minutesText(context: Context, minutes: Int): String {
    val value = AutoBackupInterval.normalize(minutes)
    return when {
        value % 1440 == 0 -> context.getString(R.string.data_backup_unit_day, value / 1440)
        value % 60 == 0 -> context.getString(R.string.data_backup_unit_hour, value / 60)
        value < 60 -> context.getString(R.string.data_backup_unit_minute, value)
        else -> context.getString(R.string.data_backup_unit_hour_minute, value / 60, value % 60)
    }
}

private fun defaultBackupFileName(): String =
    "Eta-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.eta-backup.json"
