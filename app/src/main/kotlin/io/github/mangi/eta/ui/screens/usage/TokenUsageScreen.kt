package io.github.mangi.eta.ui.screens.usage

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.TokenUsageRecordEntity
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.usage.TokenUsageExporter
import io.github.mangi.eta.data.usage.TokenUsageRecorder
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 统计窗口：近 30 天（含今天）。 */
private const val WINDOW_DAYS = 30L

/**
 * 用量统计：只读 token_usage_records 的本地记录。
 * 今日 / 近 30 天汇总、近 30 天每日趋势，以及按模型、按会话的完整排行（每行一个，不截断）。
 */
@Composable
internal fun TokenUsageScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<TokenUsageRecordEntity>()) }
    var titles by remember { mutableStateOf(emptyMap<String, String>()) }
    var exporting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val database = EtaDatabase.get(context)
            val rows = database.tokenUsageDao().recordsSince(0L)
            val names = database.conversationDao().conversationMetadataRows()
                .associate { it.id to it.title }
            rows to names
        }
        records = loaded.first
        titles = loaded.second
    }
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val modelNames = remember(providers) {
        providers.flatMap { provider -> provider.models }.associate { it.id to it.displayName }
    }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val monthStart = today.minusDays(WINDOW_DAYS - 1).atStartOfDay(zone).toInstant().toEpochMilli()
    val usage = records.filter { it.kind == TokenUsageRecorder.KIND_USAGE }
    val todayRows = usage.filter { it.createdAt >= todayStart }
    val monthRows = usage.filter { it.createdAt >= monthStart }
    val downgrades = records.filter { it.kind == TokenUsageRecorder.KIND_DOWNGRADE }
    val downgradeToday = downgrades.count { it.createdAt >= todayStart }
    val downgradeMonth = downgrades.count { it.createdAt >= monthStart }
    val noModelLabel = stringResource(R.string.usage_unknown_model)
    val byModel = monthRows
        .groupBy { it.modelId.orEmpty() }
        .map { entry -> (modelNames[entry.key] ?: entry.key.ifBlank { noModelLabel }) to entry.value }
        .sortedByDescending { sumTokens(it.second) }
    val byConversation = monthRows
        .groupBy { it.conversationId.orEmpty() }
        .filterKeys { it.isNotBlank() }
        .map { entry -> (titles[entry.key] ?: entry.key) to entry.value }
        .sortedByDescending { sumTokens(it.second) }
    val trend = remember(monthRows, zone) {
        (WINDOW_DAYS - 1 downTo 0).map { offset ->
            val day = today.minusDays(offset)
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            day to monthRows
                .filter { it.createdAt >= start && it.createdAt < end }
                .sumOf { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }
        }
    }
    val shareCsv: () -> Unit = {
        if (!exporting) {
            exporting = true
            scope.launch {
                val shareUri = runCatching {
                    withContext(Dispatchers.IO) {
                        val csv = TokenUsageExporter.build(context)
                        val dir = File(context.cacheDir, "usage").apply { mkdirs() }
                        val target = File(dir, TokenUsageExporter.defaultFileName())
                        target.writeText(csv)
                        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
                    }
                }.getOrNull()
                exporting = false
                if (shareUri == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.usage_export_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                send,
                                context.getString(R.string.usage_export_share_title),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }
    }

    MiuixScaffoldPage(title = stringResource(R.string.usage_stats_title), onBack = onBack) {
        if (records.isEmpty()) {
            item(key = "usage-empty") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp)) {
                    Text(text = stringResource(R.string.usage_empty), style = MiuixTheme.textStyles.body2)
                }
            }
        } else {
            item(key = "usage-today") {
                UsageGroup(
                    title = stringResource(R.string.usage_range_today),
                    pairs = summaryPairs(todayRows, downgradeToday),
                )
            }
            item(key = "usage-month") {
                UsageGroup(
                    title = stringResource(R.string.usage_range_month),
                    pairs = summaryPairs(monthRows, downgradeMonth),
                )
            }
            item(key = "usage-trend") {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(
                        text = stringResource(R.string.usage_trend_title),
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 8.dp),
                    )
                    TrendChart(trend)
                }
            }
            item(key = "usage-model") {
                UsageGroup(
                    title = stringResource(R.string.usage_by_model),
                    pairs = byModel.map { (name, rows) -> name to rowDetail(rows) },
                )
            }
            item(key = "usage-conversation") {
                UsageGroup(
                    title = stringResource(R.string.usage_by_conversation),
                    pairs = byConversation.map { (name, rows) -> name to rowDetail(rows) },
                )
            }
            item(key = "usage-export") {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    EtaPreferenceGroup {
                        EtaArrowPreference(
                            title = stringResource(R.string.usage_export_share),
                            summary = stringResource(R.string.usage_export_share_summary),
                            enabled = !exporting,
                            onClick = shareCsv,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun summaryPairs(rows: List<TokenUsageRecordEntity>, downgrades: Int): List<Pair<String, String>> = listOf(
    stringResource(R.string.usage_requests) to stringResource(R.string.usage_times, rows.size),
    stringResource(R.string.usage_input) to formatTokens(sum(rows) { it.inputTokens }),
    stringResource(R.string.usage_output) to formatTokens(sum(rows) { it.outputTokens }),
    stringResource(R.string.usage_reasoning) to formatTokens(sum(rows) { it.reasoningTokens }),
    stringResource(R.string.usage_cached) to formatTokens(sum(rows) { it.cachedTokens }),
    stringResource(R.string.usage_context) to formatTokens(rows.mapNotNull { it.contextTokens }.maxOrNull()),
    stringResource(R.string.usage_downgrade) to stringResource(R.string.usage_times, downgrades),
)

@Composable
private fun rowDetail(rows: List<TokenUsageRecordEntity>): String = stringResource(
    R.string.usage_row_tokens,
    formatTokens(sum(rows) { it.inputTokens }),
    formatTokens(sum(rows) { it.outputTokens }),
    rows.size,
)

private fun sum(rows: List<TokenUsageRecordEntity>, pick: (TokenUsageRecordEntity) -> Int?): Int =
    rows.sumOf { pick(it) ?: 0 }

private fun sumTokens(rows: List<TokenUsageRecordEntity>): Int =
    sum(rows) { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }

internal fun formatTokens(value: Int?): String {
    if (value == null) return "-"
    return when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

@Composable
private fun TrendChart(days: List<Pair<LocalDate, Int>>) {
    val peak = days.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(60.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        days.forEach { entry ->
            val fraction = (entry.second.toFloat() / peak).coerceIn(0.03f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .background(EtaPreferenceColors.Blue, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun UsageGroup(title: String, pairs: List<Pair<String, String>>) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 8.dp),
        )
        EtaPreferenceGroup {
            pairs.forEachIndexed { index, pair ->
                if (index > 0) EtaPreferenceDivider()
                EtaPreference(title = pair.first, summary = pair.second)
            }
        }
    }
}
