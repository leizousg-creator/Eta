package io.github.mangi.eta.fork.command

/**
 * 真机 root 命令守门人（借鉴 DSHA 的 DeviceShellPolicy，按其三档语义裁剪）。
 *
 * 为什么需要：Eta 的 terminal 在 `environment=android` + `identity=root` 下是**裸 root shell**，
 * 一条打错路径的 `rm -rf` 或一条 `dd` 就能把设备写坏，而现有工具审批只能问"要不要执行"
 * —— 用户点了允许，命令就发出去了。
 *
 * 这里做的是**命令内容**层面的拦截，补上工具审批之外的一层。只作用于 Android 宿主 root 通道：
 * Debian chroot 里的命令不经过这里，避免把日常开发误伤。
 *
 * 档位沿用 DSHA 的语义，但默认取 RELAXED（只拦不可逆操作），因为 Eta 的终端是通用开发环境，
 * STRICT 的 argv 白名单会把 python/git/npm 全拦掉。
 */
internal object DeviceCommandPolicy {
    const val BLOCKED_CODE = "POLICY_BLOCKED"

    enum class Mode(val id: String) {
        /** 只放行可完整识别的单条 argv（预留给"只读运维"场景，默认不启用）。 */
        STRICT("strict"),

        /** 放行原始命令，但拦截不可逆操作与受保护目录写入。 */
        RELAXED("relaxed"),

        /** 完全不按命令内容拦截。 */
        FULL("full"),
        ;

        companion object {
            /** 非法值一律回落 RELAXED：配置写坏时不能顺手把防护降到 FULL。 */
            fun parse(value: String?): Mode {
                val normalized = value?.trim()?.lowercase().orEmpty()
                return entries.firstOrNull { it.id == normalized } ?: RELAXED
            }
        }
    }

    /** 任何档位（FULL 除外）都拒绝的不可逆操作。 */
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
        mode: Mode = mode(),
    ): String? {
        if (mode == Mode.FULL) return null
        if (!environment.trim().equals("android", ignoreCase = true)) return null
        if (!identity.trim().equals("root", ignoreCase = true)) return null
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null

        val segments = splitSegments(trimmed)
        if (mode == Mode.STRICT) {
            for (segment in segments) {
                val argv = segment.argv()
                if (argv.isEmpty()) continue
                val name = argv.first().substringAfterLast('/')
                if (name !in strictAllowed) {
                    return "严格档只接受可识别的只读命令，已拒绝：$name。需要执行任意命令请把 root 档位切到 relaxed"
                }
            }
            return null
        }

        // RELAXED
        for (segment in segments) {
            val argv = segment.argv()
            if (argv.isEmpty()) continue
            val name = argv.first().substringAfterLast('/')
            if (name in irreversible) {
                return "已拒绝不可逆操作：$name（会改动分区/文件系统/系统状态，普通确认无法覆盖）"
            }
            if (name in mutating) {
                for (arg in argv.drop(1)) {
                    if (arg.startsWith("-")) continue
                    val normalized = arg.trim().trim('"', '\'').lowercase()
                    if (normalized.isEmpty()) continue
                    val hit = protectedPrefixes.firstOrNull { protected ->
                        normalized == protected || normalized.startsWith("$protected/")
                    }
                    if (hit != null) {
                        return "已拒绝写入受保护目录 $hit：请确认路径后再操作"
                    }
                }
            }
            // 管道里混进来的危险命令也要拦：`cat x | dd of=/dev/block/...`
            for (token in segment.split(Regex("[|&;]")).map { it.trim() }) {
                val head = token.substringBefore(' ').substringAfterLast('/')
                if (head in irreversible) {
                    return "已拒绝不可逆操作：$head（出现在组合命令里）"
                }
            }
        }
        return null
    }

    /** 当前档位；后续接设置页时把这里换成读偏好。 */
    private fun mode(): Mode = Mode.RELAXED

    private val strictAllowed = setOf(
        "id", "whoami", "pwd", "uname", "getprop", "getenforce", "ps", "pidof", "ls",
        "cat", "head", "tail", "wc", "grep", "stat", "readlink", "realpath", "df", "du",
        "free", "uptime", "printenv", "echo", "printf", "true", "false",
        "md5sum", "sha1sum", "sha256sum", "sha512sum",
    )

    private fun splitSegments(command: String): List<String> {
        val out = mutableListOf<String>()
        val builder = StringBuilder()
        var quote: Char? = null
        for (ch in command) {
            when {
                quote != null -> {
                    builder.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    builder.append(ch)
                }
                ch == ';' || ch == '\n' -> {
                    out += builder.toString()
                    builder.clear()
                }
                else -> builder.append(ch)
            }
        }
        if (builder.isNotBlank()) out += builder.toString()
        return out.filter { it.isNotBlank() }
    }

    private fun String.argv(): List<String> =
        split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
}
