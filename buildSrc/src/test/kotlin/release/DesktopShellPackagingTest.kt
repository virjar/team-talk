package release

import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assume.assumeTrue
import org.tukaani.xz.XZInputStream

/** Exercise the produced launchers and package bytes; no UI or service fixtures are required. */
class DesktopShellPackagingTest {
    @Test
    fun `POSIX shell and portable ZIP preserve private identity arguments and runtime links`() = temporary { root ->
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val runtime = File(root, "runtime/jbr/bin").apply { mkdirs() }
        File(runtime, "java").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"${'$'}@\"\n")
            setExecutable(true, false)
        }
        File(runtime.parentFile, "notice").writeText("runtime license")
        Files.createSymbolicLink(File(runtime.parentFile, "license").toPath(), File("notice").toPath())
        val bootstrap = File(root, "desktop-bootstrap.jar").apply { writeText("launcher fixture") }
        val seed = File(root, "seed.zip").apply { writeText("seed fixture") }
        val icon = File(root, "icon.png").apply { writeText("icon fixture") }
        val task = project.tasks.create("shell", AssembleDesktopShellTask::class.java).apply {
            platform.set("linux")
            arch.set("amd64")
            targetKey.set("linux-amd64")
            version.set("0.0.2")
            buildNumber.set(7)
            displayName.set("内部协作")
            installationName.set("TeamTalkPrivate")
            appId.set("com.example.private")
            serverJvmOptions.set(listOf("-Dname=内部 '名称' ${'$'}(echo unexpected)"))
            jbrExtractedRoot.set(runtime.parentFile.parentFile)
            bootstrapJar.from(bootstrap)
            seedPayloadZip.set(seed)
            iconFile.set(icon)
            outputDir.set(File(root, "output"))
        }
        task.assemble()
        val staging = File(root, "output/staging")
        val installed = File(staging, "TeamTalkPrivate")
        val launcher = File(installed, "bin/teamtalkprivate")
        assertTrue(launcher.readText().startsWith("#!/bin/sh\n"))
        val process = ProcessBuilder(launcher.absolutePath, "argument with spaces").start()
        val output = process.inputStream.bufferedReader().readLines()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertEquals("-Dname=内部 '名称' ${'$'}(echo unexpected)", output[0])
        assertEquals("-Dteamtalk.shell.launcher=${launcher.absolutePath}", output[1])
        assertEquals("argument with spaces", output.last())
        assertTrue(Files.isSymbolicLink(File(installed, "runtime/license").toPath()))
        assertTrue(File(installed, "share/applications/teamtalkprivate.desktop").readText()
            .contains("Exec=/opt/teamtalkprivate/bin/teamtalkprivate"))

        val zip = File(root, "portable.zip")
        archiveDirectoryZip(staging, zip, listOf("TeamTalkPrivate"))
        ZipFile.Builder().setFile(zip).get().use { archive ->
            assertTrue(archive.getEntry("TeamTalkPrivate/runtime/license").isUnixSymlink)
            assertEquals("notice", archive.getUnixSymlink(archive.getEntry("TeamTalkPrivate/runtime/license")))
            assertTrue(archive.getEntry("TeamTalkPrivate/bin/teamtalkprivate").unixMode and 0x49 != 0)
        }
        File(installed, "runtime/license").delete()
        archiveDirectoryZip(staging, zip, listOf("TeamTalkPrivate"))
        ZipFile.Builder().setFile(zip).get().use { archive ->
            assertEquals(null, archive.getEntry("TeamTalkPrivate/runtime/license"))
        }
    }

    @Test
    fun `Debian installer preserves revision and contains runnable system entry with real compression`() = temporary { root ->
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val staging = File(root, "staging")
        File(staging, "TeamTalkPrivate/bin/teamtalkprivate").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true, false)
        }
        val task = project.tasks.create("deb", BuildLinuxDebTask::class.java).apply {
            version.set("0.0.2")
            buildNumber.set(7)
            arch.set("amd64")
            installationName.set("TeamTalkPrivate")
            stagingRoot.set(staging)
            outputDir.set(File(root, "output"))
        }
        task.build()
        val members = mutableMapOf<String, ByteArray>()
        ArArchiveInputStream(File(root, "output/TeamTalk-0.0.2-linux-amd64.deb").inputStream()).use { ar ->
            while (true) {
                val entry = ar.nextEntry ?: break
                members[entry.name] = ar.readBytes()
            }
        }
        assertEquals("2.0\n", members.getValue("debian-binary").toString(Charsets.UTF_8))
        GZIPInputStream(members.getValue("control.tar.gz").inputStream()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: error("control file missing")
                    if (entry.name.removePrefix("./") == "control") {
                        val control = tar.readBytes().toString(Charsets.UTF_8)
                        assertTrue(control.contains("Package: teamtalkprivate\n"))
                        assertTrue(control.contains("Version: 0.0.2-7\n"))
                        break
                    }
                }
            }
        }
        val paths = mutableSetOf<String>()
        XZInputStream(members.getValue("data.tar.xz").inputStream()).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    paths += entry.name.removePrefix("./")
                    if (entry.name.removePrefix("./") == "usr/bin/teamtalkprivate") {
                        assertTrue(entry.mode and 0x49 != 0)
                        assertTrue(tar.readBytes().toString(Charsets.UTF_8).contains("/opt/teamtalkprivate/bin/teamtalkprivate"))
                    }
                }
            }
        }
        assertTrue("usr/bin/teamtalkprivate" in paths)
        assertTrue("opt/teamtalkprivate/bin/teamtalkprivate" in paths)
        assertFalse(paths.any { it.startsWith("opt/teamtalk/") })
    }

    @Test
    fun `NSIS compiles private installation paths without warnings`() = temporary { root ->
        assumeTrue(System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .any { File(it, "makensis").isFile || File(it, "makensis.exe").isFile })
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val staging = File(root, "staging")
        File(staging, "TeamTalkPrivate/TeamTalkPrivate.exe").apply { parentFile.mkdirs(); writeText("exe fixture") }
        // Use the real product icon; NSIS validates its binary format.
        val repository = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "client/desktop/packaging/icons/TeamTalk.ico").isFile }
        File(repository, "client/desktop/packaging/icons/TeamTalk.ico")
            .copyTo(File(staging, "TeamTalkPrivate/TeamTalkPrivate.ico"))
        val task = project.tasks.create("installer", BuildWindowsInstallerTask::class.java).apply {
            version.set("0.0.2")
            displayName.set("内部 ${'$'} 协作 \"预览\"")
            installationName.set("TeamTalkPrivate")
            stagingRoot.set(staging)
            outputDir.set(File(root, "output"))
        }
        task.build()
        assertTrue(File(root, "output/TeamTalk-0.0.2-setup.exe").length() > 1_024)
        val script = File(root, "output/teamtalk-installer.nsi").readText()
        assertTrue(script.contains("InstallDir \"${'$'}PROGRAMFILES64\\TeamTalkPrivate\""))
        assertFalse(script.contains("displayName.get()"))
    }

    @Test
    fun `JBR cache path follows pinned hash and payload descriptor keeps build identity and channel`() = temporary { root ->
        val properties = File(root, "jbr.properties")
        properties.writeText("baseUrl=https://example.com\nlinux-amd64.archive=runtime.tar.gz\nlinux-amd64.sha256=aaa\n")
        val first = JbrRuntimes.extractedRoot("linux-amd64", properties)
        properties.writeText(properties.readText().replace("sha256=aaa", "sha256=bbb"))
        val second = JbrRuntimes.extractedRoot("linux-amd64", properties)
        assertFalse(first == second)
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.create("payload", AssembleDesktopPayloadTask::class.java).apply {
            targetKey.set("linux-amd64")
            version.set("0.0.2")
            buildNumber.set(7)
            buildIdentity.set("0.0.2+test-source")
            channel.set("snapshot")
            minShellAbi.set(1)
            jarFiles.from(File(root, "app.jar").apply { writeText("payload fixture") })
            overlayPaths.set(emptyMap())
            payloadDir.set(File(root, "payload"))
            payloadZip.set(File(root, "payload.zip"))
        }
        task.assemble()
        val descriptor = Properties().apply { File(root, "payload/payload.properties").inputStream().use(::load) }
        assertEquals("0.0.2+test-source", descriptor.getProperty("buildIdentity"))
        assertEquals("snapshot", descriptor.getProperty("channel"))
    }

    private fun temporary(action: (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-packaging-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
}
