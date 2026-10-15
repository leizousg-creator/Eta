package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File

/** 一项能力检测结果：能干什么 + 现在是否真的可用。 */
internal data class LinuxCapabilityItem(
    val title: String,
    val purpose: String,
    val ready: Boolean,
)

/**
 * 直接看所选 rootfs 里有没有对应的可执行文件来判断"现在能干什么"，
 * 而不是只看安装标记——标记在而文件被清理、或装了一半的情况都能看出来。
 */
internal object LinuxCapabilityProbe {
    fun probe(context: Context, distribution: LinuxDistribution): List<LinuxCapabilityItem> {
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)

        fun has(vararg relative: String) = relative.any { File(rootfs, it).exists() }
        fun hasDir(vararg relative: String) = relative.any { File(rootfs, it).isDirectory }

        return listOf(
            LinuxCapabilityItem(
                title = "基础命令",
                purpose = "运行脚本、文件与管道操作",
                ready = has("bin/sh", "usr/bin/sh"),
            ),
            LinuxCapabilityItem(
                title = "文本处理",
                purpose = "grep / sed / awk 检索与改写文本",
                ready = has("usr/bin/grep", "bin/grep") && has("usr/bin/sed", "bin/sed"),
            ),
            LinuxCapabilityItem(
                title = "压缩归档",
                purpose = "tar / zip / unzip 打包与解包",
                ready = has("usr/bin/tar", "bin/tar"),
            ),
            LinuxCapabilityItem(
                title = "Python 3",
                purpose = "写脚本、做数据处理",
                ready = has("usr/local/bin/python3", "usr/bin/python3") || hasDir("opt/eta/python"),
            ),
            LinuxCapabilityItem(
                title = "uv",
                purpose = "Python 依赖与工具管理",
                ready = has("usr/local/bin/uv"),
            ),
            LinuxCapabilityItem(
                title = "Node.js / npm",
                purpose = "JS 工具链与 npm 包",
                ready = has("usr/local/bin/node", "usr/bin/node"),
            ),
            LinuxCapabilityItem(
                title = "SSH",
                purpose = "连接服务器、传文件",
                ready = has("usr/bin/ssh", "bin/ssh", "usr/bin/scp", "bin/scp"),
            ),
            LinuxCapabilityItem(
                title = "Kimi Code",
                purpose = "命令行 AI 编程助手",
                ready = has("usr/local/bin/kimi"),
            ),
            LinuxCapabilityItem(
                title = "Java 运行时",
                purpose = "jadx / smali 等需要 JVM",
                ready = has("opt/eta/apk-analysis/bin/java") || hasDir("usr/lib/jvm"),
            ),
            LinuxCapabilityItem(
                title = "APK 分析",
                purpose = "jadx 反编译、apktool 解包、smali / baksmali",
                ready = has("opt/eta/apk-analysis/bin/jadx", "usr/local/bin/jadx") &&
                    has("opt/eta/apk-analysis/bin/apktool", "usr/local/bin/apktool"),
            ),
            LinuxCapabilityItem(
                title = "网络下载",
                purpose = "curl / wget / git 获取资源",
                ready = has("usr/bin/curl", "bin/curl") || has("usr/bin/wget", "bin/wget") ||
                    has("usr/bin/git", "bin/git"),
            ),
        )
    }
}
