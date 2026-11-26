package io.github.mangi.eta.fork.command

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 真机 root 命令守门人的边界：该拦的拦，不该拦的别误伤。固定 RELAXED 语义，无档位。 */
class DeviceCommandPolicyTest {

    private fun judge(
        command: String,
        identity: String = "root",
        environment: String = "android",
    ) = DeviceCommandPolicy.blockReason(command, identity, environment)

    @Test
    fun irreversibleCommandsAreBlocked() {
        listOf(
            "dd if=/dev/zero of=/dev/block/sda",
            "mkfs.ext4 /dev/block/sda1",
            "wipefs -a /dev/block/sda",
            "mount -o rw /dev/block/sda1 /mnt",
            "reboot",
            "setenforce 0",
            "fastboot flash boot boot.img",
        ).forEach { assertNotNull("应拦截：$it", judge(it)) }
    }

    @Test
    fun dangerousSegmentInsideCombinedCommandIsBlocked() {
        assertNotNull(judge("echo hi | dd of=/dev/block/sda"))
        assertNotNull(judge("cd /data; reboot"))
    }

    @Test
    fun writesToProtectedDirectoriesAreBlocked() {
        assertNotNull(judge("rm -rf /sdcard/DCIM/Camera"))
        assertNotNull(judge("mv /sdcard/Download/a.jpg /sdcard/Pictures"))
        assertNotNull(judge("cp x /storage/emulated/0/Android/data/com.foo/files"))
    }

    @Test
    fun readsAndOrdinaryPathsAreNotBlocked() {
        assertNull(judge("ls -l /sdcard/DCIM"))
        assertNull(judge("cat /sdcard/DCIM/a.jpg"))
        assertNull(judge("rm -rf /data/local/tmp/eta/tmp"))
        assertNull(judge("getprop ro.build.version.sdk"))
        assertNull(judge("pm list packages"))
        // 这是通用开发环境：python/git/npm 之类不再被 argv 白名单误伤
        assertNull(judge("python3 /workspace/build.py"))
        assertNull(judge("git status"))
    }

    @Test
    fun onlyAppliesToAndroidRootChannel() {
        assertNull(judge("dd if=/dev/zero of=/dev/block/sda", identity = "user"))
        assertNull(judge("dd if=/dev/zero of=/dev/block/sda", environment = "debian"))
        assertNull(judge("reboot", environment = "linux"))
    }
}
