package io.github.mangi.eta.ui.screens.usage

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
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
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 数据占用快照，全部来自本地只读统计。 */
private data class DataUsageInfo(
    val version: String,
    val databaseBytes: Long,
    val conversations: Int,
    val messages: Int,
    val usageRecords: Int,
    val usageEarliest: Long?,
    val archiveRuns: Int,
)

private enum class DataUsageAction { ClearUsageOld, ClearUsageAll, ClearArchive }

/** 设置 → 数据占用：看数据库体积与条数，并做一次性清理。 */
@Composable
internal fun DataUsageScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<DataUsageInfo?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<DataUsageAction?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshKey) {
        info = withContext(Dispatchers.IO) { loadDataUsage(context) }
    }
    val doneTemplate = stringResource(R.string.data_usage_done)
    val confirmText = when (pending) {
        DataUsageAction.ClearUsageOld -> stringResource(R.string.data_usage_clear_usage_old)
        DataUsageAction.ClearUsageAll -> stringResource(R.string.data_usage_clear_usage_all)
        DataUsageAction.ClearArchive -> stringResource(R.string.data_usage_clear_archive)
        null -> ""
    }
    val runAction: (DataUsageAction) -> Unit = { action ->
        if (!busy) {
            busy = true
            scope.launch {
                val removed = withContext(Dispatchers.IO) { clearDataUsage(context, action) }
                busy = false
                pending = null
                notice = String.format(doneTemplate, removed)
                refreshKey += 1
            }
        }
    }

    MiuixScaffoldPage(title = stringResource(R.string.data_usage_title), onBack = onBack) {
        val current = info
        item(key = "data-usage-info") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(R.string.data_usage_version),
                    summary = current?.version?.ifBlank { null } ?: "-",
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = stringResource(R.string.data_usage_database),
                    summary = formatBytes(current?.databaseBytes),
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = stringResource(R.string.data_usage_conversations),
                    summary = current?.let { stringResource(R.string.data_usage_count_value, it.conversations) } ?: "-",
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = stringResource(R.string.data_usage_messages),
                    summary = current?.let { stringResource(R.string.data_usage_count_value, it.messages) } ?: "-",
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = stringResource(R.string.data_usage_usage_records),
                    summary = current?.let { stringResource(R.string.data_usage_count_value, it.usageRecords) } ?: "-",
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = stringResource(R.string.data_usage_usage_range),
                    summary = current?.usageEarliest?.let {
                        stringResource(R.string.data_usage_range_value, formatDay(it))
                    } ?: stringResource(R.string.data_usage_none),
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = stringResource(R.string.data_usage_archive),
                    summary = current?.let { stringResource(R.string.data_usage_count_value, it.archiveRuns) } ?: "-",
                )
            }
        }
        item(key = "data-usage-actions") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = stringResource(R.string.data_usage_clear_usage_old),
                    enabled = !busy,
                    onClick = { pending = DataUsageAction.ClearUsageOld },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = stringResource(R.string.data_usage_clear_usage_all),
                    enabled = !busy,
                    onClick = { pending = DataUsageAction.ClearUsageAll },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = stringResource(R.string.data_usage_clear_archive),
                    enabled = !busy,
                    onClick = { pending = DataUsageAction.ClearArchive },
                )
            }
        }
        notice?.let { message ->
            item(key = "data-usage-notice") {
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 12.dp),
                )
            }
        }
    }

    if (pending != null) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_usage_confirm_title),
            summary = stringResource(R.string.data_usage_confirm_message),
            onDismissRequest = { if (!busy) pending = null },
        ) {
            MiuixDialogActions(
                confirmText = confirmText,
                onCancel = { if (!busy) pending = null },
                onConfirm = { pending?.let(runAction) },
            )
        }
    }
}

private suspend fun loadDataUsage(context: Context): DataUsageInfo {
    val database = EtaDatabase.get(context)
    val databaseFile = context.getDatabasePath("eta.db")
    val bytes = listOf(
        databaseFile,
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
    ).sumOf { if (it.exists()) it.length() else 0L }
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrNull().orEmpty()
    return DataUsageInfo(
        version = version,
        databaseBytes = bytes,
        conversations = database.conversationDao().conversationCount(),
        messages = database.conversationDao().messageCount(),
        usageRecords = database.tokenUsageDao().recordCount(),
        usageEarliest = database.tokenUsageDao().earliestCreatedAt(),
        archiveRuns = database.runtimeRunDao().archivedRunRows().size,
    )
}

private suspend fun clearDataUsage(context: Context, action: DataUsageAction): Int {
    val database = EtaDatabase.get(context)
    return when (action) {
        DataUsageAction.ClearUsageOld -> {
            val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            database.tokenUsageDao().deleteBefore(cutoff)
        }
        DataUsageAction.ClearUsageAll -> database.tokenUsageDao().deleteAll()
        DataUsageAction.ClearArchive -> {
            val count = database.runtimeRunDao().archivedRunRows().size
            database.runtimeRunDao().deleteAllArchivedRuns()
            count
        }
    }
}

private fun formatBytes(value: Long?): String {
    if (value == null) return "-"
    return when {
        value >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", value / 1024.0 / 1024 / 1024)
        value >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", value / 1024.0 / 1024)
        value >= 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        else -> value.toString() + " B"
    }
}

private fun formatDay(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
