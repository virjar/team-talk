package release

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
    fun `Windows launcher embeds UTF-8 process manifest and removes temporary input on success and failure`() = temporary { root ->
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val repository = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "client/desktop/packaging/icons/TeamTalk.ico").isFile }
        val icon = File(repository, "client/desktop/packaging/icons/TeamTalk.ico")
        val bootstrap = jarFixture(root, "desktop-bootstrap.jar", "fixture/Probe.class")
        val installed = File(root, "package with spaces").apply { mkdirs() }
        val exe = File(installed, "TeamTalkPrivate.exe")
        val task = project.tasks.create("windowsExe", BuildWindowsExeTask::class.java).apply {
            displayName.set("内部协作")
            installationName.set("TeamTalkPrivate")
            version.set("0.0.2")
            jvmOptions.set(listOf("-Dteamtalk.server.url=https://example.com"))
            bootstrapJar.from(bootstrap)
            iconFile.set(icon)
            exeFile.set(exe)
        }
        task.build()
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(readWindowsManifest(exe).inputStream())
        val identity = document.getElementsByTagNameNS("urn:schemas-microsoft-com:asm.v1", "assemblyIdentity").item(0)
        assertEquals("TeamTalkPrivate", identity.attributes.getNamedItem("name").nodeValue)
        assertEquals("0.0.2.0", identity.attributes.getNamedItem("version").nodeValue)
        assertEquals(1, document.getElementsByTagNameNS("urn:schemas-microsoft-com:asm.v3", "application").length)
        assertEquals("UTF-8", document.getElementsByTagNameNS(
            "http://schemas.microsoft.com/SMI/2019/WindowsSettings", "activeCodePage").item(0).textContent)
        assertEquals(listOf(exe.name), installed.listFiles().orEmpty().map { it.name })
        assertFalse(net.sf.launch4j.config.ConfigPersister.getInstance().config.manifest.exists())

        assertFailsWith<net.sf.launch4j.BuilderException> {
            Launch4jRunner.build(File(installed, "Invalid.exe"), File(root, "missing.jar"), icon,
                "内部协作", "0.0.2", emptyList(), project.logger)
        }
        assertFalse(net.sf.launch4j.config.ConfigPersister.getInstance().config.manifest.exists())
        assertEquals(listOf(exe.name), installed.listFiles().orEmpty().map { it.name })
    }

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
            target.set(DesktopTarget.LINUX_AMD64)
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
                    assertFalse(entry.name.substringAfterLast('/').startsWith("._"), "AppleDouble leaked into control archive")
                    assertEquals(0L, entry.longUserId, entry.name)
                    assertEquals(0L, entry.longGroupId, entry.name)
                    assertTrue(entry.userName.isEmpty() && entry.groupName.isEmpty(), "Builder names leaked into ${entry.name}")
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
                    assertEquals(0L, entry.longUserId, entry.name)
                    assertEquals(0L, entry.longGroupId, entry.name)
                    assertTrue(entry.userName.isEmpty() && entry.groupName.isEmpty(), "Builder names leaked into ${entry.name}")
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
        assertFalse(paths.any { it.substringAfterLast('/').startsWith("._") }, "AppleDouble leaked into data archive")
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
        val repository = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "gradle/jbr.properties").isFile }
        assertEquals(DesktopTarget.values().toSet(), JbrRuntimes.load(File(repository, "gradle/jbr.properties")).keys)
        val properties = File(root, "jbr.properties")
        properties.writeText("baseUrl=https://example.com\nlinux-amd64.archive=runtime.tar.gz\nlinux-amd64.sha256=aaa\n")
        val first = JbrRuntimes.extractedRoot(DesktopTarget.LINUX_AMD64, properties)
        properties.writeText(properties.readText().replace("sha256=aaa", "sha256=bbb"))
        val second = JbrRuntimes.extractedRoot(DesktopTarget.LINUX_AMD64, properties)
        assertFalse(first == second)
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.create("payload", AssembleDesktopPayloadTask::class.java).apply {
            targetKey.set("linux-amd64")
            version.set("0.0.2")
            buildNumber.set(7)
            buildIdentity.set("0.0.2+test-source")
            channel.set("snapshot")
            minShellAbi.set(1)
            jarFiles.from(jarFixture(root, "app.jar", "app/Main.class"))
            overlayPaths.set(emptyMap())
            payloadDir.set(File(root, "payload"))
            payloadZip.set(File(root, "payload.zip"))
        }
        task.assemble()
        val descriptor = Properties().apply { File(root, "payload/payload.properties").inputStream().use(::load) }
        assertEquals("0.0.2+test-source", descriptor.getProperty("buildIdentity"))
        assertEquals("snapshot", descriptor.getProperty("channel"))
    }

    @Test
    fun `payload rejects two dependency versions defining the same runtime class`() = temporary { root ->
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.register("payload", AssembleDesktopPayloadTask::class.java).get().apply {
            targetKey.set("linux-amd64")
            version.set("0.0.2")
            buildNumber.set(7)
            buildIdentity.set("0.0.2+test-source")
            channel.set("snapshot")
            minShellAbi.set(1)
            jarFiles.from(jarFixture(root, "runtime-1.jar", "example/Runtime.class"),
                jarFixture(root, "runtime-2.jar", "example/Runtime.class"))
            overlayPaths.set(emptyMap())
            payloadDir.set(File(root, "payload"))
            payloadZip.set(File(root, "payload.zip"))
        }
        val failure = assertFailsWith<IllegalStateException> { task.assemble() }
        assertTrue(failure.message.orEmpty().contains("Duplicate payload class example/Runtime.class"))
        assertFalse(File(root, "payload.zip").exists())
    }

    /** Read the actual PE resource tree (RT_MANIFEST=24, CREATEPROCESS_MANIFEST_RESOURCE_ID=1). */
    private fun readWindowsManifest(exe: File): ByteArray {
        val bytes = exe.readBytes()
        val pe = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun word(offset: Int) = pe.getShort(offset).toInt() and 0xFFFF
        val header = pe.getInt(0x3C)
        assertEquals(0x00004550, pe.getInt(header), "PE signature")
        val optional = header + 24
        assertEquals(0x10B, word(optional), "Launch4j uses a PE32 wrapper for the bundled 64-bit JBR")
        val sections = optional + word(header + 20)
        fun fileOffset(rva: Int): Int {
            for (index in 0 until word(header + 6)) {
                val section = sections + index * 40
                val virtualAddress = pe.getInt(section + 12)
                val size = maxOf(pe.getInt(section + 8), pe.getInt(section + 16))
                if (rva >= virtualAddress && rva - virtualAddress < size) {
                    return pe.getInt(section + 20) + rva - virtualAddress
                }
            }
            error("PE RVA not mapped: $rva")
        }
        val resources = fileOffset(pe.getInt(optional + 112))
        fun entry(directory: Int, id: Int?): Int {
            val count = word(directory + 12) + word(directory + 14)
            val offset = (0 until count).map { directory + 16 + it * 8 }
                .firstOrNull { id == null || pe.getInt(it) == id }
                ?: error("PE resource missing: $id")
            return pe.getInt(offset + 4)
        }
        val type = entry(resources, 24)
        assertTrue(type < 0, "Manifest resource type must point to a directory")
        val name = entry(resources + (type and Int.MAX_VALUE), 1)
        assertTrue(name < 0, "Manifest resource id must point to a language directory")
        val data = entry(resources + (name and Int.MAX_VALUE), null)
        assertTrue(data >= 0, "Manifest language must point to resource data")
        val offset = fileOffset(pe.getInt(resources + data))
        return bytes.copyOfRange(offset, offset + pe.getInt(resources + data + 4))
    }

    private fun jarFixture(root: File, name: String, entry: String): File = File(root, name).apply {
        JarOutputStream(outputStream()).use { jar ->
            jar.putNextEntry(JarEntry(entry))
            jar.write(byteArrayOf(0))
            jar.closeEntry()
        }
    }

    private fun temporary(action: (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-packaging-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
}
