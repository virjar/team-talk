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
import kotlin.test.assertContentEquals
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
    fun `macOS packages carry a universal native launcher, JVM signature and architecture rejection`() = temporary { root ->
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val runtime = File(root, "runtime/jbr/Contents/Home/lib/server").apply { mkdirs() }
        val bootstrap = File(root, "desktop-bootstrap.jar").apply { writeText("launcher fixture") }
        val seed = File(root, "seed.zip").apply { writeText("seed fixture") }
        val icon = File(root, "icon.icns").apply { writeText("icon fixture") }
        val name = "TeamTalk 'Private"
        val displayName = "内部 '协作'"
        val url = "https://private.example.test/teamtalk/"
        fun machHeader(cpuType: Int) = byteArrayOf(
            0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte(), // MH_MAGIC_64 little-endian
            cpuType.toByte(), (cpuType ushr 8).toByte(), (cpuType ushr 16).toByte(), (cpuType ushr 24).toByte(), // cputype 小端
            0, 0, 0, 0,
        )
        val arm64Header = machHeader(0x0100000C)
        val x86Header = machHeader(0x01000007)
        // 架构检查读取 runtime 的 libjvm Mach-O 头；宿主匹配头通过检查后停在 dlopen（125）。
        val hostHeader = if (System.getProperty("os.arch") == "aarch64" ||
            System.getProperty("os.name").lowercase().contains("aarch64")) arm64Header else x86Header
        val hostArchName = if (hostHeader === arm64Header) "Apple 芯片" else "Intel 芯片"
        val oppositeHeader = if (hostHeader === arm64Header) x86Header else arm64Header

        for (target in listOf(DesktopTarget.MACOS_AARCH64, DesktopTarget.MACOS_AMD64)) {
            val runtimeHeader = if (target == DesktopTarget.MACOS_AARCH64) arm64Header else x86Header
            File(runtime, "libjvm.dylib").writeBytes(runtimeHeader)
            val output = File(root, "output/${target.key}")
            project.tasks.create(target.taskSuffix, AssembleDesktopShellTask::class.java).apply {
                this.target.set(target)
                version.set("0.0.3")
                buildNumber.set(9)
                this.displayName.set(displayName)
                installationName.set(name)
                appId.set("com.example.private")
                serverJvmOptions.set(listOf("-Dteamtalk.server.url=$url", "-Dname=$displayName"))
                jbrExtractedRoot.set(File(root, "runtime"))
                bootstrapJar.from(bootstrap)
                seedPayloadZip.set(seed)
                iconFile.set(icon)
                outputDir.set(output)
                assemble()
            }
            val contents = File(output, "staging/$name.app/Contents")
            val launcher = File(contents, "MacOS/$name")
            assertTrue(launcher.canExecute())
            // 原生启动器：universal fat（x86_64 + arm64），非脚本。
            val launcherBytes = launcher.readBytes()
            assertEquals(0xca.toByte(), launcherBytes[0], "fat magic (big-endian) expected")
            assertEquals(0xfe.toByte(), launcherBytes[1])
            assertEquals(0xba.toByte(), launcherBytes[2])
            assertEquals(0xbe.toByte(), launcherBytes[3])
            assertEquals(2, ByteBuffer.wrap(launcherBytes, 4, 4).order(ByteOrder.BIG_ENDIAN).int, "two architectures")

            // Info.plist：启动器读取的配置与 JVM 选项由打包写入。
            val document = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }.newDocumentBuilder().parse(File(contents, "Info.plist"))
            val keys = document.getElementsByTagName("key")
            fun value(key: String): org.w3c.dom.Node {
                val element = (0 until keys.length).map(keys::item).single { it.textContent == key }
                return generateSequence(element.nextSibling) { it.nextSibling }.first { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            }
            assertEquals("com.example.private", value("CFBundleIdentifier").textContent)
            assertEquals(name, value("CFBundleExecutable").textContent)
            assertEquals("0.0.3.9", value("CFBundleVersion").textContent)
            assertEquals(if (target == DesktopTarget.MACOS_AARCH64) "arm64" else "x86_64", value("LSArchitecturePriority").textContent)
            assertEquals("com.virjar.tk.desktop.shell.BootstrapMain", value("TeamTalkMainClass").textContent)
            assertEquals(url, value("TeamTalkDownloadBaseURL").textContent)
            val jvmOptions = value("TeamTalkJVMOptions").childNodes
            val options = (0 until jvmOptions.length).filter { jvmOptions.item(it).nodeType == org.w3c.dom.Node.ELEMENT_NODE }
                .map { jvmOptions.item(it).textContent }
            assertTrue("-Dteamtalk.server.url=$url" in options)
            assertTrue("-Dname=$displayName" in options)

            // 纯 JVM ad-hoc 签名：bundle seal 与主可执行的嵌入签名都存在。
            assertTrue(File(contents, "_CodeSignature/CodeResources").isFile)
            assertTrue(String(File(contents, "_CodeSignature/CodeResources").readBytes()).contains("<key>files2</key>"))
            assertTrue(containsEmbeddedSignature(launcherBytes), "launcher carries LC_CODE_SIGNATURE")

            ZipFile.Builder().setFile(File(output, target.portableArchiveName("0.0.3"))).get().use { archive ->
                val entry = archive.getEntry("$name.app/Contents/MacOS/$name")
                assertTrue(entry.unixMode and 0x49 != 0)
                assertContentEquals(launcherBytes, archive.getInputStream(entry).use { it.readBytes() })
                assertContentEquals(icon.readBytes(), archive.getInputStream(archive.getEntry("$name.app/Contents/Resources/$name.icns")).use { it.readBytes() })
            }

            // 行为验证（仅 macOS 宿主）：错架构在加载 JVM 前拒绝；匹配架构走到 dlopen 失败。
            // launcher 读取的是包内副本，头必须写入产物 runtime。
            if (!System.getProperty("os.name").startsWith("Mac")) continue
            val bundledLibjvm = File(contents, "runtime/Contents/Home/lib/server/libjvm.dylib")
            fun runWithHeader(header: ByteArray): Pair<Int, String> {
                bundledLibjvm.writeBytes(header)
                val error = File(root, "stderr.txt")
                val process = ProcessBuilder(launcher.absolutePath).redirectError(error).start()
                assertTrue(process.waitFor(15, TimeUnit.SECONDS))
                return process.exitValue() to error.readText()
            }
            if (runtimeHeader != hostHeader) {
                val (exit, stderr) = runWithHeader(runtimeHeader)
                assertEquals(126, exit, stderr)
                // 拒绝消息标注安装包自身的架构并给出下载页。
                val runtimeArchName = if (runtimeHeader === arm64Header) "Apple 芯片" else "Intel 芯片"
                assertTrue(stderr.contains(runtimeArchName), stderr)
                assertTrue(stderr.contains("${url}#download"), stderr)
            } else {
                val (exit, stderr) = runWithHeader(oppositeHeader)
                assertEquals(126, exit, stderr)
                val (okExit, okStderr) = runWithHeader(hostHeader)
                assertEquals(125, okExit, okStderr)
                assertTrue(okStderr.contains("cannot load JVM"), okStderr)
            }
            File(runtime, "libjvm.dylib").writeBytes(runtimeHeader)
        }
    }

    /** 主可执行应携带嵌入签名；手写字节读取避免 ByteBuffer 绝对/相对读歧义。 */
    private fun containsEmbeddedSignature(bytes: ByteArray): Boolean {
        fun le32(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        fun be32(offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

        fun sliceHasSignature(offset: Int): Boolean {
            if ((le32(offset).toLong() and 0xFFFFFFFFL) != 0xfeedfacfL) return false // MH_MAGIC_64
            var cursor = offset + 32
            repeat(le32(offset + 16)) {
                if (le32(cursor) == 0x1d) return true // LC_CODE_SIGNATURE
                cursor += le32(cursor + 4)
            }
            return false
        }

        if (bytes.size < 12) return false
        if (bytes[0] == 0xca.toByte() && bytes[1] == 0xfe.toByte()) {
            for (i in 0 until be32(4)) {
                val entry = 8 + i * 20
                val cpuType = be32(entry)
                if (cpuType == 0x01000007 || cpuType == 0x0100000C) {
                    if (sliceHasSignature(be32(entry + 8))) return true
                }
            }
            return false
        }
        return sliceHasSignature(0)
    }

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
    fun `Windows staging patches only the manifest and invalid signature while preserving the runtime source`() = temporary { root ->
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val javaw = legacyWindowsLauncher(root)
        val layout = windowsManifest(javaw)
        // 测试证书目录与尾记录的删除，不冒充可验证的 Authenticode 签名。
        val certificateOffset = (javaw.length().toInt() + 7) and -8
        val signed = javaw.readBytes().copyOf(certificateOffset + 16)
        ByteBuffer.wrap(signed).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(layout.securityDirectory, certificateOffset)
            putInt(layout.securityDirectory + 4, 16)
            putInt(certificateOffset, 16)
            putShort(certificateOffset + 4, 0x200.toShort())
            putShort(certificateOffset + 6, 2.toShort())
        }
        javaw.writeBytes(signed)
        val seed = File(root, "seed.zip").apply { writeText("seed fixture") }
        val task = project.tasks.create("windowsShell", AssembleDesktopShellTask::class.java).apply {
            target.set(DesktopTarget.WINDOWS_AMD64)
            version.set("0.0.2")
            installationName.set("TeamTalkPrivate")
            jbrExtractedRoot.set(File(root, "runtime"))
            seedPayloadZip.set(seed)
            iconFile.set(windowsIcon())
            outputDir.set(File(root, "output"))
        }
        task.assemble()
        val staging = File(root, "output/staging")
        val installed = File(staging, "TeamTalkPrivate/runtime")
        val patched = File(installed, "bin/javaw.exe")
        val bytes = patched.readBytes()
        assertContentEquals(signed, javaw.readBytes(), "The input runtime is shared by builds and must stay untouched")
        assertEquals(certificateOffset, bytes.size)
        assertEquals(0L, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(layout.securityDirectory))
        assertTrue(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(layout.checksumOffset) != 0)
        val allowed = listOf(layout.offset until layout.offset + layout.size,
            layout.securityDirectory until layout.securityDirectory + 8, layout.checksumOffset until layout.checksumOffset + 4)
        assertTrue(bytes.indices.all { index -> allowed.any { index in it } || signed[index] == bytes[index] },
            "Native code, other resources and the appended JAR must keep their exact bytes")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val before = factory.newDocumentBuilder().parse(signed.copyOfRange(layout.offset, layout.offset + layout.size).inputStream())
        val after = factory.newDocumentBuilder().parse(readWindowsManifest(patched).inputStream())
        val codePages = after.getElementsByTagNameNS("http://schemas.microsoft.com/SMI/2019/WindowsSettings", "activeCodePage")
        assertEquals(1, codePages.length)
        assertEquals("UTF-8", codePages.item(0).textContent)
        codePages.item(0).parentNode.removeChild(codePages.item(0))
        assertTrue(before.isEqualNode(after), "DPI, privilege, dependencies and compatibility settings must survive")
        val notice = File(installed, "TEAMTALK-MODIFICATIONS.txt").readText()
        assertTrue(notice.contains(JbrRuntimes.sha256Hex(javaw)))
        assertTrue(notice.contains(JbrRuntimes.sha256Hex(patched)))
        assertTrue(notice.contains("unsigned"))
        assertFalse(WindowsRuntimeManifest.enableUtf8(patched))
        assertContentEquals(bytes, patched.readBytes(), "A UTF-8 launcher is already complete")
        val zip = File(root, "portable.zip")
        archiveDirectoryZip(staging, zip, listOf("TeamTalkPrivate"))
        ZipFile.Builder().setFile(zip).get().use { archive ->
            assertContentEquals(bytes, archive.getInputStream(archive.getEntry("TeamTalkPrivate/runtime/bin/javaw.exe")).use { it.readBytes() })
            assertTrue(archive.getEntry("TeamTalkPrivate/runtime/TEAMTALK-MODIFICATIONS.txt") != null)
        }
    }

    @Test
    fun `Windows runtime rejects a manifest that cannot fit without rewriting PE sections`() = temporary { root ->
        val javaw = legacyWindowsLauncher(root)
        val layout = windowsManifest(javaw)
        val compact = readWindowsManifest(javaw).toString(Charsets.UTF_8)
            .replace(Regex("<([\\w:]+)([^<>]*)></\\1>"), "<$1$2/>").toByteArray(Charsets.UTF_8)
        val source = javaw.readBytes()
        compact.copyInto(source, layout.offset)
        ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).putInt(layout.descriptor + 4, compact.size)
        javaw.writeBytes(source)
        val failure = assertFailsWith<IllegalArgumentException> { WindowsRuntimeManifest.enableUtf8(javaw) }
        assertTrue(failure.message.orEmpty().contains("original slot"), failure.message)
        assertContentEquals(source, javaw.readBytes(), "An unsupported JBR must fail before any mutation")
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

    private data class WindowsManifest(val offset: Int, val size: Int, val descriptor: Int,
        val checksumOffset: Int, val securityDirectory: Int)

    private fun readWindowsManifest(exe: File): ByteArray {
        val layout = windowsManifest(exe)
        return exe.readBytes().copyOfRange(layout.offset, layout.offset + layout.size)
    }

    /** Read the actual PE resource tree (RT_MANIFEST=24, CREATEPROCESS_MANIFEST_RESOURCE_ID=1). */
    private fun windowsManifest(exe: File): WindowsManifest {
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
        return WindowsManifest(offset, pe.getInt(resources + data + 4), resources + data, optional + 64, optional + 128)
    }

    private fun windowsIcon(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "client/desktop/packaging/icons/TeamTalk.ico").isFile }
        .resolve("client/desktop/packaging/icons/TeamTalk.ico")

    /** 真实链接的 PE，使用 Java 启动器同类的旧清单；不依赖网络下载 JBR，也不伪造可执行文件头。 */
    private fun legacyWindowsLauncher(root: File): File {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val exe = File(root, "runtime/jbr/bin/javaw.exe").apply { parentFile.mkdirs() }
        Launch4jRunner.build(exe, jarFixture(root, "desktop-bootstrap.jar", "fixture/Probe.class"),
            windowsIcon(), "TeamTalk fixture", "0.0.2", emptyList(), project.logger)
        val manifest = File(root, "legacy.manifest")
        val supportedSystems = listOf("e2011457-1546-43c5-a5fe-008deee3d3f0", "35138b9a-5d96-4fbd-8e2d-a2440225f93a",
            "4a2f28e3-53b9-4441-ba9c-d69d4a4a6e38", "1f676c76-80e1-4239-95bb-83d0f6d0da78", "8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a")
        manifest.writeText("""
            <assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0" xmlns:asmv3="urn:schemas-microsoft-com:asm.v3">
            <assemblyIdentity name="javaw.exe" version="21.0.10.0" type="win32"></assemblyIdentity>
            <dependency><dependentAssembly><assemblyIdentity type="win32" name="Microsoft.Windows.Common-Controls" version="6.0.0.0" processorArchitecture="*" publicKeyToken="6595b64144ccf1df" language="*"></assemblyIdentity></dependentAssembly></dependency>
            <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3"><security><requestedPrivileges><requestedExecutionLevel level="asInvoker" uiAccess="false"></requestedExecutionLevel></requestedPrivileges></security></trustInfo>
            <asmv3:application><asmv3:windowsSettings><dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/PM</dpiAware></asmv3:windowsSettings></asmv3:application>
            <compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1"><application>${supportedSystems.joinToString("") { "<supportedOS Id=\"{$it}\"></supportedOS>" }}</application></compatibility>
            </assembly>
        """.trimIndent())
        try {
            net.sf.launch4j.config.ConfigPersister.getInstance().config.manifest = manifest
            net.sf.launch4j.Builder(object : net.sf.launch4j.Log() {
                override fun clear() = Unit
                override fun append(message: String?) = project.logger.lifecycle("launch4j fixture: {}", message)
            }).build()
        } finally { manifest.delete() }
        return exe
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
