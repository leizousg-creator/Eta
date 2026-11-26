package io.github.mangi.eta.fork.command

/**
 * 真机 root 命令守门人（借鉴 DSHA 的 DeviceShellPolicy，按不可逆操作语义裁剪）。
 *
 * 为什么需要：Eta 的 terminal 在 `environment=android` + `identity=root` 下是**裸 root shell**，
 * 一条打错路径的 `rm -rf` 或一条 `dd` 就能把设备写坏，而现有的操作审批只能问"要不要执行"
 * —— 用户点了允许，命令就发出去了。这里做的是**命令内容**层面的兜底，补上审批之外的一层。
 *
 * 只作用于 Android 宿主 root 通道：Debian chroot 里的命令不经过这里，避免把日常开发误伤。
 *
 * 固定为「只拦不可逆操作与受保护目录写入」这一档，不再暴露档位切换：
 * - 用户可见、可切的只有「操作审批」三档，它覆盖所有工具，语义清晰。
 * - 纯粹的 argv 白名单（STRICT）会把 python/git/npm 全拦掉，终端是通用开发环境，用不了；
 *   完全不拦（FULL）又等于不设防。留一个静默兜底最合适。
 * - 旧版把档位存在 SharedPreferences，覆盖安装会继承（曾出现默认严格档拦住只读命令）。
 *   现在不读也不写配置，行为恒定。
 */
internal object DeviceCommandPolicy {
    const val BLOCKED_CODE = "POLICY_BLOCKED"

    /** 任何情况下都拒绝的不可逆操作。 */
    private val irreversible = setOf(
        "dd", "mkfs", "mke2fs", "fdisk", "sfdisk", "parted", "sgdisk", "blockdev",
        "losetup", "wipefs", "setenforce", "chcon", "restorecon", "mount", "umount",
        "fastboot", "flash", "flash_image", "format", "erase", "wipe",
        "reboot", "shutdown", "poweroff", "halt", "recovery", "bootctl",
    )

    /** 写入这些目录前先拦一下：误删用户照片/其他应用数据很难恢复。 */
    private val protectedPrefixes = listOf("/dcim", "/pictures", "/android/data", "/android/obb")

    /** 会改动目标路径的命令（只检查它们的参数是否落在保护目录）。 */
    private val mutating = setOf("rm", "mv", "cp", "tee", "truncate", "shred", "mkdir", "touch", "chmod", "chown")

    /**
     * 返回拦截原因；null 表示放行。
     *
     * 只在真机 root 通道生效：chroot 属于受控 Linux 环境，普通身份本来也写不到系统目录。
     */
    fun blockReason(
        command: String,
        identity: String,
        environment: String,
    ): String? {
        if (!environment.trim().equals("android", ignoreCase = true)) return null
        if (!identity.trim().equals("root", ignoreCase = true)) return null
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null

        val segments = splitSegments(trimmed)
        for (segment in segments) {
            val argv = segment.argv()
            if (argv.isEmpty()) continue
            val name = argv.first().substringAfterLast('/')
            if (isIrreversible(name)) {
                return "已拒绝不可逆操作：$name"
            }
            if (name in mutating) {
                for (arg in argv.drop(1)) {
                    if (arg.startsWith("-")) continue
                    val normalized = normalizePath(arg.trim().trim('"', '\''))
                    if (normalized.isEmpty()) continue
                    val hit = protectedPrefixes.firstOrNull { protected ->
                        normalized == protected || normalized.startsWith("$protected/")
                    }
                    if (hit != null) return "已拒绝写入受保护目录 $hit：请确认路径后再操作"
                }
            }
            // 管道里混进来的危险命令也要拦：`cat x | dd of=/dev/block/...`
            for (token in segment.split(Regex("[|&;]")).map { it.trim() }) {
                val head = token.substringBefore(' ').substringAfterLast('/')
                if (isIrreversible(head)) return "已拒绝不可逆操作：$head（出现在组合命令里）"
            }
        }
        return null
    }

    private fun isIrreversible(name: String): Boolean =
        name.lowercase() in irreversible || irreversible.any { prefix -> name.lowercase().startsWith("$prefix.") }

    private fun normalizePath(raw: String): String {
        var path = raw.lowercase()
        val prefixes = listOf(
            Regex("^/storage/emulated/[0-9]+"),
            Regex("^/storage/[a-f0-9]{4}-[a-f0-9]{4}"),
            Regex("^/mnt/sdcard"),
            Regex("^/storage/self/primary"),
            Regex("^/sdcard"),
        )
        for (regex in prefixes) path = regex.replace(path, "")
        return path.ifEmpty { "/" }
    }

    /**
     * 按 shell 分隔符切段，简单覆盖 `;`、`&&`、`||`、`|`、换行。
     * 不追求完整 shell 语法，只保证"组合命令里藏了一条 dd/rm"能被看到。
     */
    private fun splitSegments(command: String): List<String> {
        val out = mutableListOf<String>()
        val builder = StringBuilder()
        var quote = '\u0000'
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (quote != '\u0000') {
                builder.append(c)
                if (c == quote && (i == 0 || command[i - 1] != '\\')) quote = '\u0000'
                i += 1
                continue
            }
            when {
                c == '\'' || c == '"' -> { quote = c; builder.append(c) }
                c == '\\' && i + 1 < command.length -> {
                    builder.append(c).append(command[i + 1]); i += 2; continue
                }
                c == '\n' || c == ';' -> { flush(out, builder) }
                c == '&' && i + 1 < command.length && command[i + 1] == '&' -> {
                    flush(out, builder); i += 2; continue
                }
                c == '|' -> {
                    flush(out, builder)
                    if (i + 1 < command.length && command[i + 1] == '|') i += 1
                }
                else -> builder.append(c)
            }
            i += 1
        }
        flush(out, builder)
        return out.filter { it.isNotBlank() }
    }

    private fun flush(out: MutableList<String>, builder: StringBuilder) {
        val part = builder.toString().trim()
        if (part.isNotEmpty()) out.add(part)
        builder.setLength(0)
    }

    /** 极简 argv 切分：按空白，保留引号包裹的整段。 */
    private fun String.argv(): List<String> {
        val out = mutableListOf<String>()
        val builder = StringBuilder()
        var quote = '\u0000'
        for (c in this) {
            when {
                quote != '\u0000' -> {
                    if (c == quote) quote = '\u0000' else builder.append(c)
                }
                c == '\'' || c == '"' -> quote = c
                c.isWhitespace() -> { if (builder.isNotEmpty()) { out.add(builder.toString()); builder.setLength(0) } }
                else -> builder.append(c)
            }
        }
        if (builder.isNotEmpty()) out.add(builder.toString())
        return out
    }
}
