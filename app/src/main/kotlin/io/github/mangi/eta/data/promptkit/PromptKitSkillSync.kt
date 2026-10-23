package io.github.mangi.eta.data.promptkit

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File

/**
 * 把已启用的技能镜像一份到 Linux 环境的 `/root/skills/`。
 *
 * 为什么必须走 Root：chroot 版 rootfs 的 `root/` 是 `drwx------ root root`，
 * App 进程连目录都进不去（实测 uid 10360 连 `ls` 都 Permission denied），
 * 在 App 进程里用 File API 建目录必然失败 —— 之前的实现就是这么报「无法创建」的。
 * 所以整段拷贝交给 App 自己的 Root 通道（[BoundedRootCommandExecutor]）执行，
 * 顺带保证 chroot 内文件属主是 root。
 *
 * 边界：
 *  - 只从应用技能目录往 Linux rootfs 拷，绝不反向写回；
 *  - 未授予 Root 时明确报告，不尝试降级方案；
 *  - 技能 id 只做白名单字符校验并单引号转义，脚本里不拼接任何模型/用户文本；
 *  - Linux 环境未就绪时直接返回原因，不代装环境。
 */
object PromptKitSkillSync {
    data class Result(val ok: Boolean, val message: String)

    /** 技能 id 只允许这些字符，避免拼进 shell 脚本时被注入。 */
    private val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,64}$")

    fun sync(context: Context, force: Boolean = false): Result {
        val startedAt = System.currentTimeMillis()
        // 随包分发的技能包：先把缺的技能补进应用技能目录，再镜像到 Linux 环境。
        // 安装器内部已自行兜底，这里不用 runCatching（避免类型推断歧义）。
        val bundled: Int = try {
            SkillBundleInstaller.ensureInstalled(context)
        } catch (error: Exception) {
            0
        }
        if (bundled > 0) AndroidAgentLogger.info("promptkit sync: bundle installed skills=$bundled")
        val result = syncInternal(context, force)
        // 纯文件取证：EtaTrace 这条链路在真机上出现过「同步跑了但一行都没有」的情况，
        // 所以每次同步都落一个最小标记文件，排查时不必先赌日志可用。
        runCatching {
            val marker = File(context.applicationContext.filesDir, "promptkit/.last-sync.json")
            marker.parentFile?.mkdirs()
            marker.writeText(
                "{\"at\":$startedAt,\"costMs\":${System.currentTimeMillis() - startedAt}," +
                    "\"ok\":${result.ok},\"message\":${jsonQuote(result.message)}}",
            )
        }
        return result
    }

    private fun jsonQuote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    private fun syncInternal(context: Context, force: Boolean): Result {
        val appContext = context.applicationContext
        val distribution = LinuxEnvironmentSettingsRepository.current(appContext)
        val backend = LinuxEnvironmentSettingsRepository.backend(appContext, distribution)
        val rootfs = LinuxEnvironmentPaths.rootfsDir(appContext, distribution, backend)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
            return Result(false, "Linux 环境未就绪（${distribution.wireName}），未同步技能")
        }
        if (RootAccess.state.value.isGranted.not()) {
            return Result(false, "未授予 Root 授权，无法写入 Linux 环境目录")
        }
        val skills = runCatching { SkillRuntime.createIndexService(appContext).listInstalledSkills() }
            .getOrElse { throwable -> return Result(false, "读取技能列表失败：${throwable.javaClass.simpleName}") }
        if (skills.isEmpty()) return Result(true, "没有已启用的技能，未同步")

        val targetRoot = File(rootfs, "root/skills").absolutePath
        // 幂等：技能集合（id+rootPath）没变、且目标目录已经有内容时直接跳过，
        // 反复关开总开关就是瞬时的，不再每次都重拷 125 个目录。
        // 签名只取 id 集合：rootPath 在不同调用间可能不一致（此前就是它让签名每次都变、缓存永不命中），
        // 而「装了/删了/启停了技能」一定会改变 id 集合，足以触发重拷；就地改某个技能内容不再自动重拷，
        // 需要时用 sync_skills（force=true）手动强制。
        val signature = skills.map { it.id }.sorted().joinToString("\n")
            .let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
            .joinToString("") { "%02x".format(it) }
        val signatureFile = File(appContext.filesDir, "promptkit/.skill-sync-signature")
        // 这里**不能**用「目标目录里有几个条目」当条件：rootfs/root 是 root 拥有的 0700 目录，
        // App 进程列不了它（list() 返回 null → 计 0），跳过判断因此永远不成立、每次都重拷。
        // 改以签名文件为唯一依据：它只在一次成功同步之后才写，且就在 App 自己可读的目录里。
        val stored = runCatching { signatureFile.readText().trim() }.getOrNull()
        if (!force && !stored.isNullOrEmpty() && stored == signature) {
            AndroidAgentLogger.info("promptkit.sync skip sig=${signature.take(8)} skills=${skills.size}")
            return Result(true, "已是最新（技能集合未变，共 ${skills.size} 项）")
        }
        val script = buildString {
            append("T=").append(quote(targetRoot)).append('\n')
            append("mkdir -p \"\$T\" || exit 1\n")
            var skipped = 0
            skills.forEach { entry ->
                val id = entry.id
                if (!SAFE_ID.matches(id)) {
                    skipped++
                    return@forEach
                }
                append("mkdir -p \"\$T/").append(id).append("\" && cp -R ")
                    .append(quote(File(entry.rootPath).absolutePath)).append("/. \"\$T/").append(id).append("/\"\n")
            }
            append("chmod -R a+rX \"\$T\"\n")
            append("ls -1 \"\$T\" | wc -l\n")
            if (skipped > 0) append("echo skipped=").append(skipped).append("\n")
        }

        val executor = BoundedRootCommandExecutor(AndroidAgentLogger)
        AndroidAgentLogger.info(
            "promptkit.sync start skills=${skills.size} force=$force sig=${signature.take(8)} stored=${stored?.take(8) ?: "none"}",
        )
        return try {
            val result = executor.execute(script, timeoutMillis = 30_000, maxOutputBytes = 64 * 1024)
            if (result.exitCode != 0) {
                AndroidAgentLogger.info(
                    "promptkit.sync fail exit=${result.exitCode} timeout=${result.timedOut} err=${result.stderr.trim().take(120)}",
                )
                Result(false, "同步失败（root 退出码 ${result.exitCode}）：${result.stderr.trim().take(200)}")
            } else {
                val count = result.stdout.lineSequence().map { it.trim() }.firstOrNull { it.toIntOrNull() != null }
                AndroidAgentLogger.info("promptkit.sync ok items=${count ?: skills.size} target=$targetRoot")
                runCatching {
                    signatureFile.parentFile?.mkdirs()
                    signatureFile.writeText(signature)
                }
                Result(true, "已同步到 $targetRoot（目录现有 ${count ?: skills.size} 项）")
            }
        } catch (throwable: Exception) {
            Result(false, "同步失败：${throwable.javaClass.simpleName}")
        } finally {
            runCatching { executor.close() }
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
