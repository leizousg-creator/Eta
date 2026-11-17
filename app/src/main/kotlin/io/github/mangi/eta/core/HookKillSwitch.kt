package io.github.mangi.eta.core

import java.io.File

/**
 * Hook 应急总开关：只靠一个文件就能让模块在所有进程里彻底不介入。
 *
 * 为什么要有：`Prefs` 开关要经过 RemotePreferences 往返，且某个 hook 把界面或
 * SYSTEM 进程搞坏时，用户往往已经点不动设置页。文件开关只需要 root 下
 * `touch`/`rm` 一行，重启对应进程立即生效，是最后一道自救手段。
 *
 * 路径固定用包名，因为注入进程（system_server、SystemUI、小 V）拿不到 Eta 的
 * Context，只能用绝对路径判断。
 */
internal object HookKillSwitch {

    private const val PACKAGE = "io.github.mangi.eta"

    /** 存在即停用全部 hook。 */
    const val OFF_FILE = "/data/data/$PACKAGE/files/hook_off"

    /** 存在即额外关闭推理（只影响模型侧，不影响 hook 安装）。 */
    const val REASONING_OFF_FILE = "/data/data/$PACKAGE/files/reasoning_off"

    fun isHookDisabled(): Boolean = exists(OFF_FILE)

    fun isReasoningDisabled(): Boolean = exists(REASONING_OFF_FILE)

    private fun exists(path: String): Boolean =
        runCatching { File(path).exists() }.getOrDefault(false)
}
