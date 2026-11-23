package io.github.mangi.eta.core

import java.io.File

/**
 * Hook 应急总开关：只靠一个文件就能让模块在所有进程里彻底不介入。
 *
 * 为什么要有：`Prefs` 开关要经过 RemotePreferences 往返，且某个 hook 把界面或
 * SYSTEM 进程搞坏时，用户往往已经点不动设置页。文件开关只需要 root 下
 * `touch`/`rm` 一行，重启对应进程立即生效，是最后一道自救手段。
 *
 * **路径必须在注入进程可读的位置**：早期版本把它放在
 * `/data/data/io.github.mangi.eta/files/`（`drwx------`，仅 Eta 自己的 uid 可进入），
 * 而注入进程是 system_server(1000) / SystemUI / 小V，`File.exists()` 恒为 false，
 * 开关形同虚设——排查"识屏被抢"时因此误判过一轮。
 *
 * 现在按可读性从高到低检查三个位置，任一存在即停用全部 hook：
 *  - `/data/system/`：system_server 自己的目录，最可靠
 *  - `/data/local/tmp/`：root 可写，部分 ROM 允许非 root 读取
 *  - `/sdcard/Download/`：用户最容易操作（有存储权限的进程可读）
 */
internal object HookKillSwitch {

    private const val PACKAGE = "io.github.mangi.eta"

    /** 存在即停用全部 hook。 */
    val OFF_FILES = listOf(
        "/data/system/eta_hook_off",
        "/data/local/tmp/eta_hook_off",
        "/sdcard/Download/eta_hook_off",
    )

    /** 历史路径（Eta 私有目录），保留兼容但实际对注入进程不可读。 */
    const val LEGACY_OFF_FILE = "/data/data/$PACKAGE/files/hook_off"

    /** 存在即额外关闭推理（只影响模型侧，不影响 hook 安装）。 */
    const val REASONING_OFF_FILE = "/data/data/$PACKAGE/files/reasoning_off"

    fun isHookDisabled(): Boolean = OFF_FILES.any { exists(it) } || exists(LEGACY_OFF_FILE)

    fun isReasoningDisabled(): Boolean = exists(REASONING_OFF_FILE)

    /** 首次命中的路径，便于日志里说明是从哪关掉的。 */
    fun activeOffFile(): String? = OFF_FILES.firstOrNull { exists(it) } ?: LEGACY_OFF_FILE.takeIf { exists(it) }

    private fun exists(path: String): Boolean =
        runCatching { File(path).exists() }.getOrDefault(false)
}
