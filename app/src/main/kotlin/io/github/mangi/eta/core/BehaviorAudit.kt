package io.github.mangi.eta.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 行为核查：把「隐形功能到底有没有真的跑起来」从「要盯着看」变成「导诊断包时自动算」。
 *
 * 只读自留日志（按天文件），按标记名精确统计（日志格式固定：时间 [线程] 标记 内容），
 * 并给出**按天分布**——所以「上周三分享入口触发没触发」这种问题能直接看出来。
 * 不猜测、不推断：命中就写命中，没见过就写没见过，并说明「没见过不等于坏了，可能只是没用到」。
 */
internal object BehaviorAudit {

    private data class Probe(
        val title: String,
        val areas: Set<String>,
        val meaning: String,
    )

    private data class DayStat(
        val label: String,
        val counts: Map<String, Int>,
        val lastLines: Map<String, String>,
        /** 配对成功的「开始 → 结束」耗时，毫秒；没走到收尾的会话不在里面。 */
        val durations: List<Long>,
    )

    private val probes = listOf(
        Probe(
            "会话开始",
            setOf("run.start"),
            "每发起一次会话都该有一条；可与会话结束条数对照。",
        ),
        Probe(
            "会话结束",
            setOf("run.end"),
            "每次会话正常结束都该有一条；明显少于会话开始说明有会话没走到收尾。",
        ),
        Probe(
            "会话失败 / 取消",
            setOf("run.fail", "run.cancel"),
            "出错或手动停止时才出现，平时为空正常。",
        ),
        Probe(
            "并发与内存闸门",
            setOf("run.gate", "run.queue", "run.queue.hold", "run.queue.release", "run.queue.timeout"),
            "内存紧张或并发到上限时才出现：run.gate=内存把并发上限压低，run.queue=进排队。",
        ),
        Probe(
            "重任务互斥（编译同时只跑一个）",
            setOf("heavy.start", "heavy.queue", "heavy.reject", "heavy.stale-release", "heavy.end"),
            "跑编译／构建类命令时才有；出现 heavy.queue 说明互斥生效并把后续任务排队了。",
        ),
        Probe(
            "检查点写入与清理",
            setOf("ckpt.start", "ckpt.remove", "ack.remove"),
            "有会话就会写检查点；ckpt.remove 多说明会话在正常收尾。",
        ),
        Probe(
            "中断会话恢复",
            setOf("ckpt.recover"),
            "应用打开后恢复未完成会话时出现；平时为空正常。",
        ),
        Probe(
            "运行进度通知（里程碑汇报）",
            setOf("run.progress.start", "run.progress.stop"),
            "有会话在跑就该 start；全部结束才 stop。只有 start 没有 stop 说明通知可能没撤掉。",
        ),
        Probe(
            "开机续跑提示",
            setOf("boot.resume-notice"),
            "开机时检查点里还有未完成会话才出现。",
        ),
        Probe(
            "分享入口",
            setOf("share.intent", "share.apply"),
            "从别的应用分享内容进来时成对出现；只有 intent 没有 apply 说明内容没灌进输入框。",
        ),
        Probe(
            "诊断包自动导出",
            setOf("diag.auto", "diag.auto.fail"),
            "每周自动导一次；只有到过点才会出现，失败会记 diag.auto.fail。",
        ),
        Probe(
            "自动任务失败自愈",
            setOf("task.retry", "task.giveup"),
            "自动任务失败时才会出现：task.retry=已排重试，task.giveup=不重试（动过手／非网络类）。",
        ),
        Probe(
            "虚拟屏路由",
            setOf("vscr"),
            "用到虚拟屏操作时出现。",
        ),
        Probe(
            "无障碍动作",
            setOf("a11y"),
            "做了屏幕点击／读取操作时出现。",
        ),
    )

    fun report(context: Context): String {
        val perDay = EtaTrace.dailyDumps(context)
        val days = perDay.map { (label, text) -> parse(label, text) }
        val totalLines = days.sumOf { day -> day.counts.values.sum() }
        return buildString {
            append("行为核查（导诊断包时自动生成，不用你盯着看）\n")
            append("生成时间：")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                .append('\n')
            append("覆盖天数：").append(days.size)
            if (days.isNotEmpty()) {
                append("（").append(days.first().label).append(" ~ ").append(days.last().label).append("）")
            }
            append("，日志共 ").append(totalLines).append(" 行\n")
            append("读法：✅ 这些天触发过；⚠️ 这些天没见过——不等于坏了，可能只是没用到。\n")
            append("按天保留最近 7 天（每天另有体积上限），更早的已经滚掉。\n\n")
            probes.forEach { probe ->
                val hitsByDay = days.map { day -> day.label to probe.areas.sumOf { day.counts[it] ?: 0 } }
                val total = hitsByDay.sumOf { it.second }
                append(if (total > 0) "✅ " else "⚠️ ").append(probe.title)
                append("    命中 ").append(total).append(" 次\n")
                if (days.isNotEmpty()) {
                    append("     按天：")
                        .append(hitsByDay.joinToString("、") { (label, count) -> "$label ×$count" })
                        .append('\n')
                }
                append("     说明：").append(probe.meaning).append('\n')
                days.asReversed().firstNotNullOfOrNull { day ->
                    probe.areas.mapNotNull { day.lastLines[it] }.lastOrNull()
                }?.let { last ->
                    append("     最近一条：").append(last).append('\n')
                }
                append('\n')
            }
            val starts = days.sumOf { it.counts["run.start"] ?: 0 }
            val ends = days.sumOf { it.counts["run.end"] ?: 0 }
            val failed = days.sumOf { (it.counts["run.fail"] ?: 0) + (it.counts["run.cancel"] ?: 0) }
            append("== 这轮账目 ==\n")
            append("会话：开始 ").append(starts).append("、结束 ").append(ends)
                .append("、失败或取消 ").append(failed)
            if (starts > 0) append("（占开始 ").append(failed * 100 / starts).append("%）")
            append('\n')
            val durations = days.flatMap { it.durations }.sorted()
            if (durations.isEmpty()) {
                append("平均会话时长：算不出来（没有能配上对的开头／结尾）\n")
            } else {
                append("平均会话时长：").append(formatDuration(durations.average()))
                    .append("，中位 ").append(formatDuration(durations[durations.size / 2].toDouble()))
                    .append("（可配对 ").append(durations.size).append(" 次；没走到收尾的不计）\n")
            }
            append("自动任务：重试 ").append(days.sumOf { it.counts["task.retry"] ?: 0 })
                .append(" 次、不重试收场 ").append(days.sumOf { it.counts["task.giveup"] ?: 0 })
                .append(" 次\n\n")
            append("== 这些天出现过的全部标记（合计）==\n")
            val merged = HashMap<String, Int>()
            days.forEach { day ->
                day.counts.forEach { (area, count) -> merged[area] = (merged[area] ?: 0) + count }
            }
            merged.entries.sortedByDescending { it.value }.forEach { (area, count) ->
                append("  ").append(area).append("  x").append(count).append('\n')
            }
        }
    }

    private val AREA_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,40}")

    private fun parse(label: String, text: String): DayStat {
        val counts = HashMap<String, Int>()
        val lastLines = HashMap<String, String>()
        val startedAt = HashMap<String, Long>()
        val durations = ArrayList<Long>()
        text.lineSequence().forEach { line ->
            val parts = line.trim().split(' ', limit = 5)
            if (parts.size < 4) return@forEach
            val area = parts[3]
            // 标记名不一定带点（vscr、a11y、app…），只要求「字母开头 + 常见符号」，
            // 否则这些功能会被误报成「没见过」。
            if (!AREA_PATTERN.matches(area)) return@forEach
            counts[area] = (counts[area] ?: 0) + 1
            lastLines[area] = line.trim().take(170)
            // 顺便把「开始 → 结束」配上对，用来算平均会话时长；只有成对的才算数。
            val runId = line.substringAfter("runId=", "").substringBefore(' ')
            if (runId.isNotBlank()) {
                val stamp = stampOf(parts[0], parts[1])
                if (stamp != null) {
                    if (area == "run.start") startedAt[runId] = stamp
                    if (area == "run.end") {
                        startedAt.remove(runId)?.let { began ->
                            if (stamp >= began) durations.add(stamp - began)
                        }
                    }
                }
            }
        }
        return DayStat(label, counts, lastLines, durations)
    }

    /** "10-10" + "02:45:51.084" → 本地时间戳；跨天配对会差一天，但日志按天分文件，够用。 */
    private fun stampOf(day: String, time: String): Long? = runCatching {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).parse("$day $time")?.time
    }.getOrNull()

    private fun formatDuration(ms: Double): String {
        val seconds = (ms / 1000.0).toLong()
        return if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"
    }
}
