package release

import deployment.RELEASE_ARTIFACT_MANIFEST_FILE
import deployment.requireReleaseArtifact
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile

class HeadlessDistributionTest {
    private val version = ReleaseVersion("0.0.1", 1, 0, 2, 0)
    private val buildIdentity = "0.0.1+${"a".repeat(40)}.dirty"

    @Test
    fun `distribution is deterministic and checks every file including launchers and identity`() = temporary { root ->
        val directory = File(root, "bundle")
        writeHeadlessDistributionFixture(directory, version, buildIdentity)
        val first = File(directory, HeadlessDistribution.CHECKSUMS).readText()
        HeadlessDistribution.seal(directory, version, buildIdentity)
        assertEquals(first, File(directory, HeadlessDistribution.CHECKSUMS).readText())
        assertEquals(buildIdentity, requireReleaseArtifact(directory, HeadlessDistribution.ARTIFACT_TYPE,
            version.name, buildIdentity).buildIdentity)
        val props = Properties().apply { File(directory, RELEASE_ARTIFACT_MANIFEST_FILE).reader().use(::load) }
        assertEquals("21", props.getProperty("minimumJavaVersion"))
        assertEquals("2", props.getProperty("protocolMinor"))
        assertEquals(10, first.lineSequence().filter(String::isNotEmpty).count())
        listOf("bin/tt", "lib/sdk.jar", RELEASE_ARTIFACT_MANIFEST_FILE).forEach { path ->
            val file = File(directory, path)
            val original = file.readBytes()
            file.appendText("changed")
            assertFailsWith<IllegalArgumentException> { HeadlessDistribution.verify(directory, version, buildIdentity) }
            file.writeBytes(original)
        }
        File(directory, "unexpected-file").writeText("extra")
        assertFailsWith<IllegalArgumentException> { HeadlessDistribution.verify(directory, version, buildIdentity) }
        File(directory, "unexpected-file").delete()
        File(directory, "bin/tt-mcp.bat").delete()
        assertFailsWith<IllegalArgumentException> { HeadlessDistribution.verify(directory, version, buildIdentity) }
    }

    @Test
    fun `same version cannot relabel another source or protocol and missing entry points fail before sealing`() = temporary { root ->
        val directory = File(root, "bundle")
        writeHeadlessDistributionFixture(directory, version, buildIdentity)
        assertFailsWith<org.gradle.api.GradleException> {
            HeadlessDistribution.verify(directory, version, buildIdentity.replace('a', 'b'))
        }
        assertFailsWith<IllegalArgumentException> {
            HeadlessDistribution.verify(directory, version.copy(protocolMinor = 3), buildIdentity)
        }
        File(directory, "lib/sdk.jar").delete()
        assertFailsWith<IllegalArgumentException> { HeadlessDistribution.seal(directory, version, buildIdentity) }
    }

    @Test
    fun `portable ZIP retains executable entry points and independently verifies its sealed bytes`() = temporary { root ->
        val directory = File(root, "bundle")
        writeHeadlessDistributionFixture(directory, version, buildIdentity)
        val archive = File(root, HeadlessDistribution.archiveName(buildIdentity))
        HeadlessDistribution.archive(directory, archive, version, buildIdentity)
        val first = sha256(archive)
        HeadlessDistribution.archive(directory, archive, version, buildIdentity)
        assertEquals(first, sha256(archive))
        ZipFile.builder().setFile(archive).get().use { zip ->
            assertEquals(0b111101101, zip.getEntry("tt-headless/bin/tt").unixMode and 0x1FF)
            assertEquals(0b110100100, zip.getEntry("tt-headless/bin/tt.bat").unixMode and 0x1FF)
        }
        // Verification uses the archive, even when its original directory has disappeared.
        directory.deleteRecursively()
        HeadlessDistribution.verifyArchive(archive, version, buildIdentity)
        assertFailsWith<IllegalArgumentException> {
            HeadlessDistribution.verifyArchive(archive, version.copy(buildNumber = 2), buildIdentity)
        }
        val damaged = File(root, "damaged.zip")
        ZipFile.builder().setFile(archive).get().use { source ->
            ZipArchiveOutputStream(damaged).use { target ->
                source.entries.asSequence().forEach { entry ->
                    target.putArchiveEntry(ZipArchiveEntry(entry.name))
                    if (entry.name == "tt-headless/LICENSE") target.write("changed".toByteArray())
                    else source.getInputStream(entry).use { it.copyTo(target) }
                    target.closeArchiveEntry()
                }
            }
        }
        assertFailsWith<IllegalArgumentException> { HeadlessDistribution.verifyArchive(damaged, version, buildIdentity) }
    }

    @Test
    fun `symlinks cannot silently import files outside the distribution`() = temporary { root ->
        if (isWindows()) return@temporary // Creating symbolic links requires an explicit Windows host privilege.
        val directory = File(root, "bundle")
        writeHeadlessDistributionFixture(directory, version, buildIdentity)
        val foreign = File(root, "foreign").apply { writeText("outside") }
        Files.createSymbolicLink(File(directory, "linked").toPath(), foreign.toPath())
        assertFailsWith<IllegalArgumentException> { HeadlessDistribution.verify(directory, version, buildIdentity) }
    }

    @Test
    fun `all launchers diagnose the relocated artifact without a Java runtime or agent connection`() = temporary { root ->
        val directory = File(root, "package with spaces !")
        writeHeadlessDistributionFixture(directory, version, buildIdentity)
        val caller = File(root, "empty caller").apply { mkdirs() }
        listOf("tt-agent", "tt", "tt-mcp").forEach { name ->
            val result = runLauncher(directory, name, caller, listOf("--version"), File(root, "missing JDK"))
            assertEquals(0, result.first, result.second)
            assertTrue(result.second.contains("buildIdentity=$buildIdentity"))
            assertTrue(result.second.contains("minimumJavaVersion=21"))
            assertTrue(result.second.contains("distributionDirectory="))
            assertTrue(result.second.contains(directory.canonicalPath))
        }
        assertFalse(caller.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `native launchers use only bundled dependencies preserve caller paths and forward arguments`() = temporary { root ->
        val directory = File(root, "relocated SDK !")
        writeHeadlessDistributionFixture(directory, version, buildIdentity)
        writeRunnableFixture(root, directory)
        HeadlessDistribution.seal(directory, version, buildIdentity)
        val caller = File(root, "caller elsewhere").apply { mkdirs() }
        val args = listOf("--file", "relative directory/file name.txt", "--literal", "a&b")
        listOf("tt", "tt-mcp", "tt-agent").forEach { name ->
            val result = runLauncher(directory, name, caller, args, File(System.getProperty("java.home")))
            if (isWindows() && name != "tt") {
                assertEquals(1, result.first)
                assertTrue(result.second.contains("POSIX filesystem"))
            } else {
                assertEquals(0, result.first, result.second)
                assertTrue(result.second.contains("cwd=${caller.canonicalPath}"), result.second)
                assertTrue(result.second.contains("bundle=${directory.canonicalPath}"), result.second)
                assertTrue(result.second.contains("ipv4=${name == "tt-mcp"}"), result.second)
                args.forEach { assertTrue(result.second.contains("arg=$it"), result.second) }
            }
        }
    }

    private fun runLauncher(directory: File, name: String, caller: File, args: List<String>, javaHome: File): Pair<Int, String> {
        val launcher = File(directory, "bin/$name${if (isWindows()) ".bat" else ""}")
        val command = if (isWindows()) listOf("cmd.exe", "/d", "/s", "/c",
            "\"\"${launcher.absolutePath}\" ${args.joinToString(" ") { "\"$it\"" }}\"")
        else listOf(launcher.absolutePath) + args
        val log = File.createTempFile("launcher-", ".log", caller.parentFile)
        val process = ProcessBuilder(command).directory(caller).apply {
            environment()["JAVA_HOME"] = javaHome.absolutePath
            environment().remove("CLASSPATH")
            environment().remove("JAVA_TOOL_OPTIONS")
            environment().remove("JDK_JAVA_OPTIONS")
            redirectErrorStream(true)
            redirectOutput(log)
        }.start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Headless launcher timed out")
        }
        return process.exitValue() to log.readText()
    }

    private fun writeRunnableFixture(root: File, distribution: File) {
        val sources = File(root, "fixture source").apply { mkdirs() }
        val helper = File(sources, "Probe.java").apply { writeText("""
            package fixture;
            public class Probe {
                public static void run(String[] args) {
                    System.out.println("cwd=" + System.getProperty("user.dir"));
                    System.out.println("bundle=" + System.getProperty("teamtalk.headless.bundle"));
                    System.out.println("ipv4=" + System.getProperty("java.net.preferIPv4Stack", "false"));
                    for (String arg : args) System.out.println("arg=" + arg);
                }
            }
        """.trimIndent()) }
        val mains = listOf("AgentMainKt", "CliMainKt", "McpMainKt").map { name -> File(sources, "$name.java").apply {
            writeText("package com.virjar.tk.shared.agent; public class $name { public static void main(String[] args) { fixture.Probe.run(args); } }")
        } }
        val classes = File(root, "fixture classes").apply { mkdirs() }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.absolutePath, helper.absolutePath, *mains.map { it.absolutePath }.toTypedArray()))
        mapOf("sdk.jar" to "com/", "dependency.jar" to "fixture/").forEach { (name, prefix) ->
            JarOutputStream(File(distribution, "lib/$name").outputStream()).use { jar ->
                classes.walkTopDown().filter(File::isFile).forEach { file ->
                    val relative = file.relativeTo(classes).invariantSeparatorsPath
                    if (relative.startsWith(prefix)) {
                        jar.putNextEntry(JarEntry(relative))
                        jar.write(file.readBytes())
                        jar.closeEntry()
                    }
                }
            }
        }
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows")
    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("headless-artifact-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}

/** Structural fixture; executable-launcher tests replace these class entries with javac output. */
internal fun writeHeadlessDistributionFixture(directory: File, version: ReleaseVersion, buildIdentity: String) {
    File(directory, "lib").mkdirs()
    File(directory, "LICENSE").writeText("fixture license\n")
    JarOutputStream(File(directory, "lib/sdk.jar").outputStream()).use { jar ->
        listOf("AgentMainKt", "CliMainKt", "McpMainKt").forEach {
            jar.putNextEntry(JarEntry("com/virjar/tk/shared/agent/$it.class"))
            jar.write(byteArrayOf(0))
            jar.closeEntry()
        }
    }
    File(directory, "lib/runtime.properties").writeText("fixture=dependency\n")
    HeadlessDistribution.seal(directory, version, buildIdentity)
}
