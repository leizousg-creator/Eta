package io.github.mangi.eta.core

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.TextStoreMaintenance
import io.github.mangi.eta.data.model.AutoBackupInterval
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 极致自动排查 —— 巡检周期与档位。
 *
 * 与自动备份同口径（分钟），但默认更保守：一天一次巡检已经足够，
 * 需要盯现场时可以在界面上填到 5 分钟。
 */
internal object DiagnosticsPatrolInterval {

    /** 最短 5 分钟：再短会和闹钟重排互相打架。 */
    const val MIN_MINUTES = 5

    /** 最长 30 天。 */
    const val MAX_MINUTES = 30 * 24 * 60

    /** 默认 24 小时。 */
    const val DEFAULT_MINUTES = 24 * 60

    val PRESETS = listOf(5, 10, 30, 60, 180, 360, 720, 1440, 4320, 10080, 43200)

    fun normalize(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    fun millis(minutes: Int): Long = normalize(minutes).toLong() * 60_000L

    /** 「每 10 分钟」「每 6 小时」「每 3 天」这种短标签。 */
    fun label(minutes: Int): String {
        val value = normalize(minutes)
        return when {
            value < 60 -> "每 $value 分钟"
            value % 1440 == 0 -> "每 ${value / 1440} 天"
            value % 60 == 0 -> "每 ${value / 60} 小时"
            else -> "每 ${value / 60} 小时 ${value % 60} 分钟"
        }
    }
}

/**
 * 极致自动排查引擎。
 *
 * 一次巡检做三件事：
 *  1. 采集：崩溃记录、自动备份守约、巡检自身守约、文本库体量、无障碍、内存与堆、
 *     存储余量、目录规模、日志关键字、行为核查账目、EtaTrace 摘要；
 *  2. 判定：把每项按 [Level] 分级，算「距上次巡检的增量」；
 *  3. 留档：写一份人可读报告 + 一份机器可读结论 JSON，可选再落一份诊断包 zip，
 *     并按保留份数滚动清理（只动自己目录，绝不碰手动存的诊断包）。
 *
 * 不做常驻：由 [DiagnosticsPatrolAlarm] 排一次精确闹钟，跑完再排下一次；
 * 崩溃现场由 [captureNow] 立刻补一次（不需要等下一个周期）。
 */
internal object DiagnosticsPatrol {

    /** 只在这里滚动保留；手动存的诊断包一个都不碰。 */
    const val BASE_DIR = "/storage/emulated/0/ai共享文件夹/诊断包备份/巡检"

    private const val FILE_PREFIX = "Eta-巡检-"
    private const val REPORT_SUFFIX = ".md"
    private const val CONCLUSION_SUFFIX = ".json"
    private const val BUNDLE_SUFFIX = ".zip"

    private const val CHANNEL_ID = "diag_patrol"
    private const val NOTIFICATION_ID = 0x0E7C

    /** 备份目录超过这个体积就提醒（默认 5 GB）。 */
    private const val BACKUP_DIR_WARN_BYTES = 5L * 1024 * 1024 * 1024

    private const val STORAGE_NOTICE_BYTES = 500L * 1024 * 1024
    private const val STORAGE_ABNORMAL_BYTES = 100L * 1024 * 1024

    internal enum class Level(val weight: Int, val label: String) {
        OK(0, "正常"),
        NOTICE(1, "注意"),
        ABNORMAL(2, "异常"),
    }

    internal data class Finding(
        val level: Level,
        val area: String,
        val title: String,
        val detail: String,
        val suggestion: String,
    )

    internal data class Report(
        val at: Long,
        val reason: String,
        val level: Level,
        val findings: List<Finding>,
        val metrics: Map<String, String>,
        val deltas: List<String>,
        val reportFile: File?,
        val conclusionFile: File?,
        val bundleFile: File?,
    ) {
        val abnormalCount: Int get() = findings.count { it.level == Level.ABNORMAL }
        val noticeCount: Int get() = findings.count { it.level == Level.NOTICE }

        fun oneLine(): String = "等级 ${level.label}｜异常 $abnormalCount 项｜注意 $noticeCount 项"
    }

    private data class Baseline(val at: Long, val crashCount: Int, val oversized: Int)

    // ── 对外入口 ────────────────────────────────────────────────────────────

    /** 手动或定时跑一次完整巡检。 */
    suspend fun runOnce(context: Context, reason: String, exportBundle: Boolean = true): Report =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
            val now = System.currentTimeMillis()
            val dir = File(BASE_DIR).apply { runCatching { mkdirs() } }
            val baseline = readBaseline(dir)
            val findings = mutableListOf<Finding>()
            val metrics = linkedMapOf<String, String>()
            val extra = StringBuilder()
            // 日志采样留一份给「自定义检测」复用，不重复抓
            var logLines: List<String> = emptyList()

            // 1) 崩溃记录（增量判定）
            runCatching {
                val crashes = CrashRecorder.records(app)
                metrics["崩溃记录份数"] = crashes.size.toString()
                val latest = crashes.firstOrNull()
                metrics["最近崩溃时间"] = latest?.let { stamp(it.lastModified()) } ?: "无"
                if (crashes.isNotEmpty()) {
                    extra.appendLine("── 崩溃现场（最近一份前 800 字）──")
                    extra.appendLine(runCatching { latest!!.readText().take(800) }.getOrDefault("（读取失败）"))
                    extra.appendLine()
                }
                val latestStamp = latest?.let { file -> stamp(file.lastModified()) }.orEmpty()
                if (crashes.isNotEmpty() && baseline != null && crashes.size > baseline.crashCount) {
                    findings += Finding(
                        Level.ABNORMAL, "崩溃",
                        "发现新崩溃记录（${baseline.crashCount} → ${crashes.size}）",
                        "最近一份：${latest?.name.orEmpty()}（$latestStamp）",
                        "已把这份崩溃现场写进报告附录；把结论贴给 Eta 可以直接做归因。",
                    )
                } else if (crashes.isNotEmpty()) {
                    findings += Finding(
                        Level.NOTICE, "崩溃",
                        "存在 ${crashes.size} 份历史崩溃记录（本次没有新增）",
                        "最近一份：${latest?.name.orEmpty()}",
                        "确认修好后可以在设置里清理崩溃记录。",
                    )
                } else {
                    findings += Finding(Level.OK, "崩溃", "没有崩溃记录", "崩溃留证目录为空", "无需处理")
                }
            }.onFailure { metrics["崩溃检查"] = "失败：${it.safeLogType()}" }

            // 2) 自动备份守约
            runCatching {
                val enabled = settings?.autoBackupEnabled == true
                metrics["自动备份开关"] = if (enabled) "开" else "关"
                if (!enabled) {
                    findings += Finding(Level.OK, "自动备份", "自动备份未开启", "当前关闭", "需要的话在「数据备份」里打开")
                } else {
                    val minutes = AutoBackupInterval.effectiveMinutes(
                        settings?.autoBackupIntervalMinutes ?: 0,
                        settings?.autoBackupIntervalHours ?: 24,
                    )
                    val intervalMs = minutes * 60_000L
                    val lastAt = settings?.autoBackupLastAt ?: 0L
                    metrics["自动备份周期"] = DiagnosticsPatrolInterval.label(minutes)
                    metrics["上次成功备份"] = if (lastAt > 0) stamp(lastAt) else "从未"
                    metrics["距上次备份"] = if (lastAt > 0) humanDuration(now - lastAt) else "—"
                    when {
                        lastAt <= 0L -> findings += Finding(
                            Level.NOTICE, "自动备份", "开启后还没有成功备份过", "上次成功时间：从未",
                            "确认备份目录可写（共享存储权限）。",
                        )
                        now - lastAt > intervalMs * 3 -> findings += Finding(
                            Level.ABNORMAL, "自动备份", "疑似漏跑（超过 3 个周期没落盘）",
                            "周期 ${DiagnosticsPatrolInterval.label(minutes)}，距上次备份 ${humanDuration(now - lastAt)}",
                            "可能被系统清理了闹钟或后台被限；到「数据备份」点一次「立即备份一次」，再观察下一轮。",
                        )
                        intervalMs <= 24 * 3_600_000L && now - lastAt > intervalMs * 2 -> findings += Finding(
                            Level.NOTICE, "自动备份", "比设定周期晚",
                            "周期 ${DiagnosticsPatrolInterval.label(minutes)}，距上次备份 ${humanDuration(now - lastAt)}",
                            "短周期依赖亮屏唤醒，锁屏久了会晚一点，属正常范围。",
                        )
                        else -> findings += Finding(
                            Level.OK, "自动备份", "按周期正常落盘",
                            "周期 ${DiagnosticsPatrolInterval.label(minutes)}，距上次 ${humanDuration(now - lastAt)}",
                            "无需处理",
                        )
                    }
                }
            }.onFailure { metrics["自动备份检查"] = "失败：${it.safeLogType()}" }

            // 3) 巡检自身守约（说明闹钟有没有被系统清掉）
            runCatching {
                val minutes = DiagnosticsPatrolInterval.normalize(settings?.diagPatrolIntervalMinutes ?: DiagnosticsPatrolInterval.DEFAULT_MINUTES)
                val lastAt = settings?.diagPatrolLastAt ?: 0L
                metrics["巡检周期"] = DiagnosticsPatrolInterval.label(minutes)
                metrics["上次巡检"] = if (lastAt > 0) stamp(lastAt) else "首次"
                if (lastAt > 0 && now - lastAt > DiagnosticsPatrolInterval.millis(minutes) * 3) {
                    findings += Finding(
                        Level.ABNORMAL, "巡检调度", "巡检自己没按周期跑（闹钟可能被清）",
                        "周期 ${DiagnosticsPatrolInterval.label(minutes)}，上次巡检 ${humanDuration(now - lastAt)} 前",
                        "已自动重排；若连续两轮都这样，检查电池优化白名单。",
                    )
                }
            }.onFailure { metrics["巡检守约检查"] = "失败：${it.safeLogType()}" }

            // 4) 文本库体量
            runCatching {
                val report = TextStoreMaintenance.inspect(EtaDatabase.get(app))
                metrics["文本库字段数"] = report.scannedFields.toString()
                metrics["文本库字符数"] = "%,d".format(report.scannedChars)
                metrics["越线字段数"] = report.oversizedFields.toString()
                val top = report.topFields.joinToString("；") {
                    "${it.ownerTable}#${it.ownerId}.${it.field} ${"%,d".format(it.charCount)}"
                }
                if (top.isNotEmpty()) metrics["体量前三字段"] = top
                when {
                    report.oversizedFields <= 0 -> findings += Finding(
                        Level.OK, "文本库", "体量正常，没有越线字段",
                        "共 ${report.scannedFields} 个字段 / ${"%,d".format(report.scannedChars)} 字符",
                        "无需处理",
                    )
                    baseline != null && baseline.oversized > 0 && report.oversizedFields >= baseline.oversized ->
                        findings += Finding(
                            Level.ABNORMAL, "文本库", "越线字段没有减少（瘦身可能没生效）",
                            "越线 ${baseline.oversized} → ${report.oversizedFields} 个；单字段上限 %,d 字符".format(io.github.mangi.eta.data.db.ChunkedTextDao.MAX_FIELD_CHARS),
                            "到「功能自检」看文本库体量并手动跑一次瘦身，再等下一轮巡检对比。",
                        )
                    else -> findings += Finding(
                        Level.NOTICE, "文本库", "存在越线字段（等自动瘦身处理）",
                        "越线 ${report.oversizedFields} 个 / 共 ${report.scannedFields} 个字段",
                        "下次启动或下一次瘦身会截断，留意下一轮巡检是否减少。",
                    )
                }
            }.onFailure { metrics["文本库检查"] = "失败：${it.safeLogType()}" }

            // 5) 无障碍服务
            runCatching {
                val enabled = android.provider.Settings.Secure.getString(
                    app.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                val on = enabled.split(':').any { it.startsWith(app.packageName) }
                metrics["无障碍服务"] = if (on) "已开启" else "未开启"
                findings += if (on) {
                    Finding(Level.OK, "无障碍", "无障碍服务在线", "GUI 工具与触发器可用", "无需处理")
                } else {
                    Finding(Level.NOTICE, "无障碍", "无障碍服务未开启", "当前系统里没有启用", "GUI 工具、触发器会不可用，需要时在系统设置里打开")
                }
            }.onFailure { metrics["无障碍检查"] = "失败：${it.safeLogType()}" }

            // 6) 内存与堆
            runCatching {
                val runtime = Runtime.getRuntime()
                val used = runtime.totalMemory() - runtime.freeMemory()
                val max = runtime.maxMemory()
                val manager = app.getSystemService(ActivityManager::class.java)
                val info = ActivityManager.MemoryInfo()
                manager.getMemoryInfo(info)
                metrics["堆用量"] = "%s / %s".format(bytes(used), bytes(max))
                metrics["系统可用内存"] = bytes(info.availMem)
                metrics["低内存标记"] = if (info.lowMemory) "是" else "否"
                val ratio = if (max > 0) used.toDouble() / max.toDouble() else 0.0
                when {
                    info.lowMemory -> findings += Finding(
                        Level.ABNORMAL, "内存", "系统报告低内存", "可用 ${bytes(info.availMem)}",
                        "先清掉后台大应用；多开会话时把工具档调低一档。",
                    )
                    ratio > 0.85 -> findings += Finding(
                        Level.NOTICE, "内存", "堆占用偏高（${(ratio * 100).toInt()}%）",
                        "堆 %s / %s".format(bytes(used), bytes(max)),
                        "长会话可以新建对话，或让上下文压缩生效。",
                    )
                    else -> findings += Finding(
                        Level.OK, "内存", "内存与堆正常",
                        "堆 %s / %s，系统可用 %s".format(bytes(used), bytes(max), bytes(info.availMem)),
                        "无需处理",
                    )
                }
            }.onFailure { metrics["内存检查"] = "失败：${it.safeLogType()}" }

            // 7) 存储余量
            runCatching {
                val stat = StatFs(Environment.getExternalStorageDirectory().path)
                val free = stat.availableBytes
                metrics["共享存储余量"] = bytes(free)
                findings += when {
                    free < STORAGE_ABNORMAL_BYTES -> Finding(
                        Level.ABNORMAL, "存储", "共享存储余量不足", "剩余 ${bytes(free)}",
                        "先清旧备份与诊断包，否则备份会失败。",
                    )
                    free < STORAGE_NOTICE_BYTES -> Finding(
                        Level.NOTICE, "存储", "共享存储余量偏低", "剩余 ${bytes(free)}",
                        "可以调小自动备份的保留份数。",
                    )
                    else -> Finding(Level.OK, "存储", "存储余量充足", "剩余 ${bytes(free)}", "无需处理")
                }
            }.onFailure { metrics["存储检查"] = "失败：${it.safeLogType()}" }

            // 8) 目录规模
            runCatching {
                val patrol = dirSize(dir)
                val backupDir = File("/storage/emulated/0/ai共享文件夹/数据备份记录")
                val backupSize = if (backupDir.isDirectory) dirSize(backupDir) else 0L
                metrics["巡检目录体积"] = bytes(patrol)
                metrics["自动备份目录体积"] = bytes(backupSize)
                metrics["本机备份目录"] = if (backupDir.isDirectory) backupDir.absolutePath else "不存在"
                if (backupSize > BACKUP_DIR_WARN_BYTES) {
                    findings += Finding(
                        Level.NOTICE, "目录规模", "自动备份目录偏大",
                        "已达 ${bytes(backupSize)}",
                        "到「数据备份」把保留份数调小，或手动清旧份。",
                    )
                }
            }.onFailure { metrics["目录检查"] = "失败：${it.safeLogType()}" }

            // 9) 日志关键字（导出这一刻抓一次，事后是抓不到的）
            runCatching {
                val lines = shellCapture(600).also { logLines = it }
                val crashLines = shellCapture(400, "crash")
                val tomb = tombstones()
                val fatal = lines.count { it.contains("FATAL EXCEPTION") } +
                    crashLines.count { it.contains("FATAL EXCEPTION") }
                val anr = lines.count { it.contains("ANR in") || it.contains("ANR ") } +
                    crashLines.count { it.contains("ANR in") || it.contains("ANR ") }
                val native = lines.count { it.contains("libc") && it.contains("Fatal signal") } +
                    crashLines.count { it.contains("Fatal signal") || it.contains("signal 11") || it.contains("signal 6") } +
                    tomb.size
                metrics["日志采样行数"] = lines.size.toString()
                metrics["crash 缓冲行数"] = crashLines.size.toString()
                metrics["墓碑份数"] = tomb.size.toString()
                metrics["FATAL 行数"] = fatal.toString()
                metrics["ANR 行数"] = anr.toString()
                metrics["native 崩溃行数"] = native.toString()
                if (lines.isNotEmpty()) {
                    extra.appendLine("── 日志尾部（最多 120 行）──")
                    extra.append(lines.takeLast(120)).appendLine()
                    extra.appendLine()
                }
                if (crashLines.isNotEmpty()) {
                    extra.appendLine("── crash 缓冲尾部（最多 60 行；main 被刷爆时靠它）──")
                    extra.appendLine(crashLines.takeLast(60).joinToString("\n"))
                    extra.appendLine()
                }
                if (tomb.isNotEmpty()) {
                    extra.appendLine("── 最近原生崩溃墓碑（/data/tombstones）──")
                    tomb.forEach { extra.appendLine(it) }
                    extra.appendLine()
                }
                findings += when {
                    fatal > 0 || native > 0 -> Finding(
                        Level.ABNORMAL, "日志", "日志里抓到 FATAL / native 崩溃",
                        "FATAL $fatal 行，native $native 行",
                        "报告里已附日志尾部，可直接贴给 Eta 定位。",
                    )
                    anr > 0 -> Finding(Level.NOTICE, "日志", "日志里有 ANR 记录", "ANR $anr 行", "留意是否固定出现在某功能上")
                    else -> Finding(Level.OK, "日志", "本次采样没有崩溃关键字", "采样 ${lines.size} 行", "无需处理")
                }
            }.onFailure { metrics["日志检查"] = "失败：${it.safeLogType()}（无 Root 时可能取不到）" }

            // 9.5) 自定义检测（规则在设置页手输保存，改规则不用重出包）
            runCatching {
                val rules = DiagnosticsPatrolRules.load().filter { it.enabled && it.patterns.isNotEmpty() }
                if (rules.isNotEmpty()) {
                    val reportText = buildString {
                        metrics.forEach { (key, value) -> appendLine("$key=$value") }
                        appendLine(extra.toString())
                        findings.forEach { appendLine("${it.level.label}｜${it.area}｜${it.title}｜${it.detail}") }
                    }
                    var hitRules = 0
                    rules.forEach { rule ->
                        val hits = DiagnosticsPatrolRules.scan(rule, logLines, reportText)
                        if (hits.isEmpty()) return@forEach
                        hitRules += 1
                        findings += Finding(
                            DiagnosticsPatrolRules.levelOf(rule),
                            "自定义",
                            rule.name,
                            "命中 ${hits.size} 处｜" + hits.first().trim().take(200),
                            rule.note.ifBlank { "来自你保存的自定义检测项（${rule.target.label}）。" },
                        )
                        extra.appendLine("── 自定义检测：${rule.name} ──")
                        hits.take(10).forEach { extra.appendLine(it.trim().take(200)) }
                        extra.appendLine()
                    }
                    metrics["自定义检测"] = "规则 ${rules.size} 条，命中 $hitRules 条"
                } else {
                    metrics["自定义检测"] = "没有启用的规则"
                }
            }.onFailure { metrics["自定义检测"] = "失败：${it.safeLogType()}" }

            // 10) 行为核查账目
            runCatching {
                val audit = BehaviorAudit.report(app)
                val zeros = audit.lines().count { it.contains("0 次") }
                metrics["账目零次项"] = zeros.toString()
                if (audit.isNotBlank()) {
                    extra.appendLine("── 行为核查账目 ──")
                    extra.appendLine(audit)
                    extra.appendLine()
                }
                if (zeros > 0) {
                    findings += Finding(
                        Level.NOTICE, "账目", "有 $zeros 项记账为 0 次",
                        "这些功能本周期没有被触发过",
                        "如果确实用过却没记账，说明埋点没生效，值得查。",
                    )
                }
            }.onFailure { metrics["账目检查"] = "失败：${it.safeLogType()}" }

            // 11) EtaTrace 摘要
            runCatching {
                val summary = EtaTrace.summary(app)
                if (summary.isNotBlank()) {
                    metrics["EtaTrace"] = summary.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120)
                    extra.appendLine("── EtaTrace 摘要 ──")
                    extra.appendLine(summary)
                    extra.appendLine()
                }
            }.onFailure { metrics["EtaTrace 检查"] = "失败：${it.safeLogType()}" }

            // 结论
            val level = findings.maxByOrNull { it.level.weight }?.level ?: Level.OK
            val deltas = buildDeltas(baseline, now, settings?.diagPatrolLastLevel, metrics)
            val base = FILE_PREFIX + stampFileName(now)
            val reportFile = runCatching { File(dir, base + REPORT_SUFFIX).apply { writeText(renderReport(now, reason, findings, metrics, deltas, extra.toString())) } }.getOrNull()
            val conclusionFile = runCatching { File(dir, base + CONCLUSION_SUFFIX).apply { writeText(renderConclusion(now, reason, level, findings, metrics, deltas)) } }.getOrNull()
            val bundleFile = if (exportBundle) {
                runCatching {
                    val target = File(dir, base + BUNDLE_SUFFIX)
                    target.writeBytes(DiagnosticsBundle.collect(app))
                    target
                }.getOrNull()
            } else {
                null
            }
            val keep = (settings?.diagPatrolKeepCount ?: 30).coerceIn(1, 60)
            prune(dir, keep)

            val report = Report(now, reason, level, findings, metrics, deltas, reportFile, conclusionFile, bundleFile)
            runCatching {
                SettingsDataStore.updateSettings {
                    it.copy(
                        diagPatrolLastAt = now,
                        diagPatrolLastFile = reportFile?.name ?: "",
                        diagPatrolLastLevel = level.label,
                        diagPatrolLastSummary = report.oneLine(),
                    )
                }
            }
            val onlyAbnormal = settings?.diagPatrolNotifyOnlyAbnormal ?: true
            if (!onlyAbnormal || level != Level.OK) notify(app, report)
            runCatching { DiagnosticsPatrolAlarm.refresh(app) }
            report
        }

    /** 崩溃现场立刻补一次取证：不等下一个周期，且不导出诊断包（够快、够轻）。 */
    fun captureNow(context: Context, reason: String) {
        val app = context.applicationContext
        runCatching {
            Thread {
                runCatching { runBlocking { runOnce(app, reason = reason, exportBundle = false) } }
            }.apply { isDaemon = true }.start()
        }
    }

    /** 最近一次巡检结论的文件（给界面用）。 */
    fun latestReport(): File? = File(BASE_DIR).listFiles()
        ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(REPORT_SUFFIX) }
        ?.maxByOrNull { it.lastModified() }

    fun reports(limit: Int = 20): List<File> = File(BASE_DIR).listFiles()
        ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(REPORT_SUFFIX) }
        ?.sortedByDescending { it.lastModified() }
        ?.take(limit)
        ?: emptyList()

    // ── 内部实现 ────────────────────────────────────────────────────────────

    private fun readBaseline(dir: File): Baseline? {
        val latest = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(CONCLUSION_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        val text = runCatching { latest.readText() }.getOrNull() ?: return null
        return Baseline(
            at = number(text, "at") ?: latest.lastModified(),
            crashCount = (number(text, "crashCount") ?: 0L).toInt(),
            oversized = (number(text, "oversizedFields") ?: 0L).toInt(),
        )
    }

    private fun number(text: String, key: String): Long? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()

    private fun buildDeltas(baseline: Baseline?, now: Long, lastLevel: String?, metrics: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        if (baseline == null) {
            out += "首次巡检，没有可比基线"
            return out
        }
        out += "距上次巡检 ${humanDuration(now - baseline.at)}（上一次等级 ${lastLevel?.ifBlank { "—" } ?: "—"}）"
        val crashes = metrics["崩溃记录份数"]?.toIntOrNull()
        if (crashes != null) out += "崩溃记录 ${baseline.crashCount} → $crashes"
        val oversized = metrics["越线字段数"]?.toIntOrNull()
        if (oversized != null) out += "越线字段 ${baseline.oversized} → $oversized"
        return out
    }

    private fun renderReport(
        at: Long,
        reason: String,
        findings: List<Finding>,
        metrics: Map<String, String>,
        deltas: List<String>,
        extra: String,
    ): String = buildString {
        val level = findings.maxByOrNull { it.level.weight }?.level ?: Level.OK
        appendLine("Eta 极致自动排查 · 巡检报告")
        appendLine("时间：${stamp(at)}　原因：$reason")
        appendLine("结论：${level.label}　异常 ${findings.count { it.level == Level.ABNORMAL }} 项 / 注意 ${findings.count { it.level == Level.NOTICE }} 项 / 共 ${findings.size} 项")
        appendLine()
        appendLine("── 距上次的变化 ──")
        deltas.forEach { appendLine("· $it") }
        appendLine()
        appendLine("── 逐项排查 ──")
        findings.sortedByDescending { it.level.weight }.forEach { f ->
            appendLine("[${f.level.label}] ${f.area}｜${f.title}")
            appendLine("    细节：${f.detail}")
            appendLine("    建议：${f.suggestion}")
        }
        appendLine()
        appendLine("── 采集指标 ──")
        metrics.forEach { (k, v) -> appendLine("$k：$v") }
        if (extra.isNotBlank()) {
            appendLine()
            appendLine("── 附录 ──")
            appendLine(extra)
        }
    }

    private fun renderConclusion(
        at: Long,
        reason: String,
        level: Level,
        findings: List<Finding>,
        metrics: Map<String, String>,
        deltas: List<String>,
    ): String = buildString {
        appendLine("{")
        appendLine("  \"at\": $at,")
        appendLine("  \"time\": \"${stamp(at)}\",")
        appendLine("  \"reason\": \"${escape(reason)}\",")
        appendLine("  \"level\": \"${level.label}\",")
        appendLine("  \"abnormal\": ${findings.count { it.level == Level.ABNORMAL }},")
        appendLine("  \"notice\": ${findings.count { it.level == Level.NOTICE }},")
        appendLine("  \"crashCount\": ${metrics["崩溃记录份数"] ?: 0},")
        appendLine("  \"oversizedFields\": ${metrics["越线字段数"] ?: 0},")
        appendLine("  \"deltas\": [")
        deltas.forEachIndexed { index, d -> appendLine("    \"${escape(d)}\"${if (index == deltas.lastIndex) "" else ","}") }
        appendLine("  ],")
        appendLine("  \"metrics\": {")
        metrics.entries.forEachIndexed { index, (k, v) ->
            appendLine("    \"${escape(k)}\": \"${escape(v)}\"${if (index == metrics.size - 1) "" else ","}")
        }
        appendLine("  },")
        appendLine("  \"findings\": [")
        findings.forEachIndexed { index, f ->
            appendLine("    {\"level\": \"${f.level.label}\", \"area\": \"${escape(f.area)}\", \"title\": \"${escape(f.title)}\", \"detail\": \"${escape(f.detail)}\", \"suggestion\": \"${escape(f.suggestion)}\"}${if (index == findings.lastIndex) "" else ","}")
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun prune(dir: File, keep: Int) {
        runCatching {
            val all = dir.listFiles()?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) } ?: return
            val bases = all.map { it.name.substringBeforeLast('.') }.distinct().sortedDescending()
            bases.drop(keep).forEach { base ->
                all.filter { it.name.startsWith(base) }.forEach { it.delete() }
            }
        }
    }

    private fun notify(context: Context, report: Report) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "极致自动排查", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "巡检发现异常时的提醒"
                    },
                )
            }
            val intent = Intent(context, io.github.mangi.eta.ui.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pending = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val first = report.findings.firstOrNull { it.level == report.level }
            val notification = android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Eta 巡检：${report.level.label}")
                .setContentText(first?.title ?: report.oneLine())
                .setStyle(android.app.Notification.BigTextStyle().bigText(report.findings.take(5).joinToString("\n") { "· ${it.area}：${it.title}" }))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    /** buffer 为 null 抓 main；crash 缓冲单独抓，main 被刷爆时靠它兜住崩溃现场。 */
    private fun shellCapture(maxLines: Int, buffer: String? = null): List<String> = runCatching {
        val args = mutableListOf("logcat", "-d", "-v", "time")
        if (buffer != null) {
            args += "-b"
            args += buffer
        }
        args += "-t"
        args += maxLines.toString()
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        runCatching { process.waitFor() }
        lines.takeLast(maxLines)
    }.getOrDefault(emptyList())

    /** 最近的原生崩溃墓碑（读不到就返回空，不影响巡检其他项）。 */
    private fun tombstones(limit: Int = 5): List<String> = runCatching {
        File("/data/tombstones").listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.map { "${it.name}｜${bytes(it.length())}｜${stamp(it.lastModified())}" }
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun dirSize(dir: File): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    private fun stampFileName(millis: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(millis))

    private fun humanDuration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 60 -> "${seconds} 秒"
            seconds < 3600 -> "${seconds / 60} 分钟"
            seconds < 86_400 -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分钟"
            else -> "${seconds / 86_400} 天 ${(seconds % 86_400) / 3600} 小时"
        }
    }

    private fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val kb = value / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
}
