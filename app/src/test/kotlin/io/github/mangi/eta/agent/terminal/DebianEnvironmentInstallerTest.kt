package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebianEnvironmentInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactSelectionUsesPinnedTrixieIntegrityMetadata() {
        val artifact = DebianEnvironmentInstaller.artifactForAbis(
            listOf("armeabi-v7a", "arm64-v8a", "x86_64"),
        )

        requireNotNull(artifact)
        assertEquals("13", artifact.version)
        assertEquals("debian-trixie-aarch64-pd-v4.29.0.tar.xz", artifact.fileName)
        assertTrue(artifact.url.contains("termux/proot-distro/releases/download/v4.29.0"))
        assertEquals(GITHUB_PROXY_PREFIXES.size, artifact.preferredUrls.size)
        assertTrue(artifact.preferredUrls.all { it.startsWith("https://gh") })
        assertTrue(artifact.preferredUrls.all { it.contains(artifact.fileName) })
        assertEquals(64, artifact.sha256.length)
        assertEquals(35_409_704L, artifact.sizeBytes)
    }

    @Test
    fun unsupportedAbiDoesNotGuessAnArtifact() {
        assertNull(DebianEnvironmentInstaller.artifactForAbis(listOf("armeabi-v7a", "x86")))
    }

    @Test
    fun readinessUsesInstallerMarkerOnly() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val marker = File(rootfs, LinuxEnvironmentPaths.READY_MARKER)

        assertFalse(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        marker.writeText("version=13\n")
        assertTrue(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath))
    }

    @Test
    fun baseToolsetContainsGlibcAndAgentEssentials() {
        val packages = DebianEnvironmentInstaller.AGENT_PACKAGES

        assertTrue(packages.containsAll(listOf("bash", "coreutils", "git", "jq", "ripgrep", "sqlite3", "xz-utils")))
        assertFalse(packages.contains("nodejs"))
        assertFalse(packages.contains("npm"))
        assertFalse(packages.contains("python3"))
        assertFalse(packages.contains("python3-pip"))
        assertEquals(packages.distinct(), packages)
    }

    @Test
    fun aptMirrorsKeepDomesticCandidatesBeforeOfficialFallback() {
        assertEquals(listOf("aliyun", "ustc", "official"), DebianEnvironmentInstaller.APT_MIRRORS.map { it.id })
        assertTrue(DebianEnvironmentInstaller.APT_MIRRORS.dropLast(1).all { it.archiveBaseUrl.startsWith("https://mirrors.") })
        assertEquals(
            "https://security.debian.org/debian-security",
            DebianEnvironmentInstaller.APT_MIRRORS.first().securityBaseUrl,
        )
        val script = DebianEnvironmentInstaller.aptMirrorScript()
        assertTrue(script.contains("apt-get -o Acquire::Retries=2"))
        // 2026-11 实测：清华源在部分移动网络对 InRelease 返回 403，改用阿里云打头、中科大兜底。
        assertFalse(script.contains("mirrors.tuna.tsinghua.edu.cn"))
        assertTrue(script.contains("mirrors.aliyun.com"))
        assertTrue(script.contains("mirrors.ustc.edu.cn"))
    }

    @Test
    fun aptMirrorScriptIsValidPosixShell() {
        val process = ProcessBuilder("sh", "-n").start()
        process.outputStream.use { output ->
            output.write(DebianEnvironmentInstaller.aptMirrorScript().toByteArray())
        }
        assertEquals(0, process.waitFor())
    }
}
