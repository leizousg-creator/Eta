package io.github.mangi.eta.data.promptkit

import java.io.File

/**
 * 技能包范围策略：决定「哪些技能算技能包内的技能」。
 *
 * 为什么用文件而不是数据库/上下文：
 *  - 技能索引（[io.github.mangi.eta.agent.skill.SkillRuntime]）在构建索引时就要判断，
 *    那条路径拿不到 Context，也不该为它引入依赖；
 *  - 两个标记都放在技能根目录（`files/skills/`）下，随技能一起存在，天然一致。
 *
 * 文件：
 *  - `.skill-bundle-ids.txt`：技能包内技能 id，每行一个；由 [SkillBundleInstaller] 写入；
 *  - `.bundle-only`：内容为 `1` 表示开启「仅使用技能包内技能」，缺省按开启处理。
 */
object SkillBundlePolicy {
    const val IDS_FILE = ".skill-bundle-ids.txt"
    const val FLAG_FILE = ".bundle-only"

    /** 技能包内技能 id 集合；文件不存在时返回空集（表示不限制）。 */
    fun bundleIds(skillsRoot: File): Set<String> {
        val file = File(skillsRoot, IDS_FILE)
        if (!file.isFile) return emptySet()
        return runCatching {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }.getOrDefault(emptySet())
    }

    /** 是否开启「仅使用技能包内技能」；标记文件不存在时视为开启。 */
    fun isBundleOnly(skillsRoot: File): Boolean {
        val file = File(skillsRoot, FLAG_FILE)
        if (!file.isFile) return true
        return runCatching { file.readText().trim() != "0" }.getOrDefault(true)
    }

    fun setBundleOnly(skillsRoot: File, enabled: Boolean) {
        runCatching {
            skillsRoot.mkdirs()
            File(skillsRoot, FLAG_FILE).writeText(if (enabled) "1" else "0")
        }
    }

    /** 该技能是否允许参与索引/同步。 */
    fun allows(skillsRoot: File, skillId: String, bundleIds: Set<String> = bundleIds(skillsRoot)): Boolean {
        if (bundleIds.isEmpty()) return true
        if (!isBundleOnly(skillsRoot)) return true
        return skillId in bundleIds
    }
}
