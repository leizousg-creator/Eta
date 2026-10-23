package io.github.mangi.eta.data.promptkit

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 随包分发的技能包（assets 里的 zip）安装器。
 *
 * 目的：把「成品包自带技能」这件事做掉 —— 用户装上 App 后，只要打开提示词包开关，
 * 这批技能就已经在应用技能目录里，紧接着被 [PromptKitSkillSync] 同步进 Linux 环境。
 *
 * 边界：
 *  - **只补缺，不覆盖**：目标技能目录已存在就整目录跳过，用户改过的内容不会被冲掉；
 *  - 同版本也会检查缺项：用户删掉某个技能后，再次触发会补回来；
 *  - zip 内路径做白名单校验，拒绝 `..`、绝对路径和非法技能 id；
 *  - 失败只记日志、不抛出，绝不影响提示词包的启用流程；
 *  - 安装完把包内技能 id 清单写入 [SkillBundlePolicy.IDS_FILE]，供「仅使用技能包内技能」判定。
 */
object SkillBundleInstaller {
    /** 技能包版本；换新包时递增，触发一次补装。 */
    const val BUNDLE_VERSION = 5

    private const val BUNDLE_ASSET = "skill-bundles/pojia-v4.zip"
    private const val BUNDLE_ROOT = "skills"
    private const val MARKER_NAME = ".skill-bundle.json"
    private val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,64}$")

    /** 结果：本次新装的技能数（0 表示已齐全）。调用方需在 IO 线程。 */
    fun ensureInstalled(context: Context): Int =
        runCatching { install(context.applicationContext) }
            .onFailure { AndroidAgentLogger.warn("skill bundle install failed: ${it.javaClass.simpleName}") }
            .getOrDefault(0)

    private fun install(appContext: Context): Int {
        val skillsDir = File(appContext.filesDir, "skills")
        val marker = File(skillsDir, MARKER_NAME)
        if (!skillsDir.exists() && !skillsDir.mkdirs()) return 0

        val allIds = LinkedHashSet<String>()
        val created = HashSet<String>()
        val skipped = HashSet<String>()
        var hasPreviousMarker = marker.isFile && marker.readText().contains("\"version\":$BUNDLE_VERSION")

        ZipInputStream(BufferedInputStream(appContext.assets.open(BUNDLE_ASSET))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val relative = entry.name.substringAfter("$BUNDLE_ROOT/", "")
                val skillId = relative.substringBefore('/')
                if (relative.isBlank() || skillId.isBlank() || !SAFE_ID.matches(skillId)) {
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                allIds.add(skillId)
                val target = File(skillsDir, skillId)
                if (skillId in skipped || (skillId !in created && target.exists())) {
                    skipped.add(skillId)
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                if (!entry.isDirectory) {
                    val out = File(skillsDir, relative)
                    val parent = out.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }
                    out.outputStream().use { sink -> zip.copyTo(sink) }
                    created.add(skillId)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // 包内技能 id 清单：供范围策略使用（与是否本次新装无关）。
        runCatching {
            File(skillsDir, SkillBundlePolicy.IDS_FILE).writeText(allIds.sorted().joinToString("\n"))
        }

        if (hasPreviousMarker && created.isEmpty()) {
            return 0
        }
        runCatching {
            marker.writeText(
                "{\"version\":$BUNDLE_VERSION,\"installed\":${created.size},\"bundle\":${allIds.size}," +
                    "\"at\":${System.currentTimeMillis()}}",
            )
        }
        AndroidAgentLogger.info(
            "skill bundle installed: version=$BUNDLE_VERSION new=${created.size} " +
                "skipped=${skipped.size} bundle=${allIds.size}",
        )
        return created.size
    }
}
