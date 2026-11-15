package io.github.mangi.eta.fork.command

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 真机 root 命令守门人的边界：该拦的拦，不该拦的别误伤。 */
class DeviceCommandPolicyTest {

    private fun judge(
        command: String,
        identity: String = "root",
        environment: String = "android",
        mode: DeviceCommandPolicy.Mode = DeviceCommandPolicy.Mode.RELAXED,
    ) = DeviceCommandPolicy.blockReason(command, identity, environment, mode)

    @Test
    fun `不可逆操作被拦`() {
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
    fun `组合命令里的危险段也拦`() {
        assertNotNull(judge("echo hi | dd of=/dev/block/sda"))
        assertNotNull(judge("cd /data; reboot"))
    }

    @Test
    fun `受保护目录的写操作被拦`() {
        assertNotNull(judge("rm -rf /sdcard/DCIM/Camera"))
        assertNotNull(judge("mv /sdcard/Download/a.jpg /sdcard/Pictures"))
        assertNotNull(judge("cp x /storage/emulated/0/Android/data/com.foo/files"))
    }

    @Test
    fun `读操作与普通路径不误伤`() {
        assertNull(judge("ls -l /sdcard/DCIM"))
        assertNull(judge("cat /sdcard/DCIM/a.jpg"))
        assertNull(judge("rm -rf /data/local/tmp/eta/tmp"))
        assertNull(judge("getprop ro.build.version.sdk"))
        assertNull(judge("pm list packages"))
    }

    @Test
    fun `只作用于真机 root 通道`() {
        assertNull(judge("dd if=/dev/zero of=/dev/block/sda", identity = "user"))
        assertNull(judge("dd if=/dev/zero of=/dev/block/sda", environment = "debian"))
        assertNull(judge("reboot", environment = "linux"))
    }

    @Test
    fun `full 档不拦，strict 档收紧到只读白名单`() {
        assertNull(judge("reboot", mode = DeviceCommandPolicy.Mode.FULL))
        assertNull(judge("ls /sdcard", mode = DeviceCommandPolicy.Mode.STRICT))
        assertNotNull(judge("pm install /data/local/tmp/a.apk", mode = DeviceCommandPolicy.Mode.STRICT))
    }

    @Test
    fun `非法档位回落 relaxed`() {
        assertNull(judge("ls /sdcard", mode = DeviceCommandPolicy.Mode.parse("bogus")))
        assertNotNull(judge("reboot", mode = DeviceCommandPolicy.Mode.parse("bogus")))
        assertNull(judge("reboot", mode = DeviceCommandPolicy.Mode.parse("FULL")))
    }
}
