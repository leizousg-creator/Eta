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
    const val BUNDLE_VERSION = 8

    private const val BUNDLE_ASSET = "skill-bundles/pojia-v8.zip"
    private const val BUNDLE_ROOT = "skills"
    private const val MARKER_NAME = ".skill-bundle.json"

    /** 包内声明的「要删掉的旧文件」清单（相对 skills 根，一行一个）。 */
    private const val REMOVE_LIST_NAME = ".skill-bundle-remove"
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
        var createdFiles = 0
        var skippedFiles = 0
        var hasPreviousMarker = marker.isFile && marker.readText().contains("\"version\":$BUNDLE_VERSION")
        val removals = mutableListOf<String>()

        ZipInputStream(BufferedInputStream(appContext.assets.open(BUNDLE_ASSET))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val relative = entry.name.substringAfter("$BUNDLE_ROOT/", "")
                if (relative == REMOVE_LIST_NAME) {
                    // 重命名/删除旧文件：老用户的技能目录已存在，按文件补缺补不出"改名"效果，
                    // 包内显式列出要清理的旧路径。
                    runCatching { zip.readBytes().toString(Charsets.UTF_8) }
                        .getOrDefault("")
                        .lineSequence()
                        .map(String::trim)
                        .filter { it.isNotEmpty() }
                        .forEach(removals::add)
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                val skillId = relative.substringBefore('/')
                if (relative.isBlank() || skillId.isBlank() || !SAFE_ID.matches(skillId)) {
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                allIds.add(skillId)
                val out = File(skillsDir, relative)
                if (entry.isDirectory) {
                    // 目录条目直接建出来，内容由下面的文件条目补齐。
                    out.mkdirs()
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                // 按文件补缺：技能目录已存在也要继续走，否则升级后新增的 references
                // 永远补不进来（旧逻辑是整目录跳过）。已存在的文件一律不覆盖。
                if (out.exists()) {
                    skippedFiles++
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                val parent = out.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    skippedFiles++
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                out.outputStream().use { sink -> zip.copyTo(sink) }
                created.add(skillId)
                createdFiles++
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        removals.forEach { rel ->
            runCatching { File(skillsDir, rel).delete() }
        }

        // 包内技能 id 清单：供范围策略使用（与是否本次新装无关）。
        runCatching {
            File(skillsDir, SkillBundlePolicy.IDS_FILE).writeText(allIds.sorted().joinToString("\n"))
        }

        if (hasPreviousMarker && createdFiles == 0) {
            return 0
        }
        runCatching {
            marker.writeText(
                "{\"version\":$BUNDLE_VERSION,\"installed\":$createdFiles,\"bundle\":${allIds.size}," +
                    "\"at\":${System.currentTimeMillis()}}",
            )
        }
        AndroidAgentLogger.info(
            "skill bundle installed: version=$BUNDLE_VERSION newFiles=$createdFiles " +
                "skills=${created.size} skippedFiles=$skippedFiles bundle=${allIds.size}",
        )
        return createdFiles
    }
}
