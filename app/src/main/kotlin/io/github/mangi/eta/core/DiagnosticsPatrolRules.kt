package io.github.mangi.eta.core

import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「极致自动排查」的自定义检测项。
 *
 * 目的：加一条检测规则不用改源码、不用重出包 —— 在设置页手输名字 + 关键字（或正则），
 * 选好检测范围（日志采样 / 报告全文 / 文件内容）和等级，保存成一份人能看懂的 JSON；
 * 巡检时按这些规则扫一遍，命中就按你设的等级写进报告、异常时才提醒。
 *
 * 规则文件放在巡检报告的同级目录，可以直接用文件管理器打开改，改完下次巡检就生效。
 */
internal object DiagnosticsPatrolRules {

    /** 规则文件：纯文本 JSON，可手动编辑，不参与报告滚动清理。 */
    const val FILE_PATH = "/storage/emulated/0/ai共享文件夹/诊断包备份/自定义检测.json"

    private const val TAG = "DiagnosticsPatrolRules"
    private const val MAX_RULE_FILES = 200
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_FILE_LINES = 2000
    private const val MAX_SOURCE_LINES = 5000
    private const val MAX_HITS = 50

    enum class Target(val key: String, val label: String, val hint: String) {
        LOG("log", "日志采样", "扫巡检时抓的那 600 行系统日志"),
        REPORT("report", "报告全文", "扫本次巡检的报告正文（指标、账目、日志尾部都算）"),
        FILE("file", "文件内容", "扫下面这个文件或目录里的文本"),
        ;

        companion object {
            fun of(key: String?): Target = entries.firstOrNull { it.key == key } ?: LOG
            fun next(current: Target): Target = entries[(current.ordinal + 1) % entries.size]
        }
    }

    enum class RuleLevel(val key: String, val label: String) {
        NOTICE("notice", "注意"),
        ABNORMAL("abnormal", "异常"),
        ;

        companion object {
            fun of(key: String?): RuleLevel = entries.firstOrNull { it.key == key } ?: ABNORMAL
            fun next(current: RuleLevel): RuleLevel = entries[(current.ordinal + 1) % entries.size]
        }
    }

    data class Rule(
        val name: String = "",
        val target: Target = Target.LOG,
        val path: String = "",
        val patterns: List<String> = emptyList(),
        val regex: Boolean = false,
        val level: RuleLevel = RuleLevel.ABNORMAL,
        val enabled: Boolean = true,
        val note: String = "",
    ) {
        fun summary(): String = listOf(
            target.label,
            level.label,
            if (patterns.isEmpty()) "未填关键字" else "${patterns.size} 个关键字",
            if (regex) "正则" else "包含",
            if (enabled) "开启" else "停用",
        ).joinToString("｜")
    }

    fun file(): File = File(FILE_PATH)

    fun load(): List<Rule> = runCatching {
        val f = file()
        if (!f.isFile) return emptyList()
        val array = JSONArray(f.readText())
        (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            val patterns = o.optJSONArray("patterns")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "").trim() }.filter { it.isNotEmpty() }
            }.orEmpty()
            val name = o.optString("name", "").trim()
            if (name.isBlank() && patterns.isEmpty()) return@mapNotNull null
            Rule(
                name = name.ifBlank { patterns.firstOrNull().orEmpty() },
                target = Target.of(o.optString("target")),
                path = o.optString("path", "").trim(),
                patterns = patterns,
                regex = o.optBoolean("regex", false),
                level = RuleLevel.of(o.optString("level")),
                enabled = o.optBoolean("enabled", true),
                note = o.optString("note", "").trim(),
            )
        }
    }.onFailure { Log.w(TAG, "读取自定义检测失败 ${it.javaClass.simpleName}") }.getOrDefault(emptyList())

    fun save(rules: List<Rule>): Boolean = runCatching {
        val f = file()
        f.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("name", rule.name)
                    .put("target", rule.target.key)
                    .put("path", rule.path)
                    .put("patterns", JSONArray(rule.patterns))
                    .put("regex", rule.regex)
                    .put("level", rule.level.key)
                    .put("enabled", rule.enabled)
                    .put("note", rule.note),
            )
        }
        f.writeText(array.toString(2))
        true
    }.onFailure { Log.w(TAG, "保存自定义检测失败（${it.javaClass.simpleName}）") }.getOrDefault(false)

    fun levelOf(rule: Rule): DiagnosticsPatrol.Level =
        if (rule.level == RuleLevel.ABNORMAL) DiagnosticsPatrol.Level.ABNORMAL else DiagnosticsPatrol.Level.NOTICE

    /** 按规则扫一遍，返回命中的原文行（最多 [MAX_HITS] 行）。 */
    fun scan(rule: Rule, logLines: List<String>, reportText: String): List<String> {
        val patterns = rule.patterns.filter { it.isNotBlank() }
        if (patterns.isEmpty()) return emptyList()
        val plain = if (rule.regex) emptyList() else patterns
        val regexes = if (rule.regex) {
            patterns.mapNotNull { runCatching { Regex(it) }.getOrNull() }
        } else {
            emptyList()
        }
        if (plain.isEmpty() && regexes.isEmpty()) return emptyList()
        val source = when (rule.target) {
            Target.LOG -> logLines
            Target.REPORT -> reportText.lineSequence().toList()
            Target.FILE -> fileLines(rule.path)
        }
        val hits = mutableListOf<String>()
        for (line in source) {
            if (line.isBlank()) continue
            val matched = plain.any { line.contains(it, ignoreCase = true) } ||
                regexes.any { runCatching { it.containsMatchIn(line) }.getOrDefault(false) }
            if (matched) {
                hits += line
                if (hits.size >= MAX_HITS) break
            }
        }
        return hits
    }

    /** 目标为文件时，把文件或目录里的文本按行摊平（有界，读不动就跳过）。 */
    private fun fileLines(path: String): List<String> {
        if (path.isBlank()) return emptyList()
        val root = File(path)
        if (!root.exists()) return emptyList()
        val files = if (root.isDirectory) {
            runCatching { root.walkTopDown().filter { it.isFile }.take(MAX_RULE_FILES).toList() }
                .getOrDefault(emptyList())
        } else {
            listOf(root)
        }
        val out = mutableListOf<String>()
        files.forEach { f ->
            if (out.size >= MAX_SOURCE_LINES) return@forEach
            runCatching {
                if (f.length() > MAX_FILE_BYTES) return@runCatching
                out += f.readLines().take(MAX_FILE_LINES)
            }.onFailure { Log.w(TAG, "跳过读不动的文件 ${f.name}") }
        }
        return out
    }
}
