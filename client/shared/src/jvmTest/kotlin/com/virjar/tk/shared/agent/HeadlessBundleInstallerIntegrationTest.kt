package com.virjar.tk.shared.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real temporary directories and shell launchers; no user installation or agent data directory is opened. */
class HeadlessBundleInstallerIntegrationTest {
    @Test
    fun `install and immutable upgrade preserve running payload caller cwd and external data`() = workspace { root ->
        val first = distribution(root.resolve("first extracted package"), 'a')
        val second = distribution(root.resolve("second extracted package"), 'b')
        val data = Files.createDirectory(root.resolve("agent-data"))
        Files.writeString(data.resolve("credentials.properties"), "external-user-data")
        val prefix = root.resolve("installed tools")
        val installed = execute("install-bundle", prefix, first)
        val oldBundle = assertNotNull(installed.bundle).directory
        assertNull(HeadlessBundleInstaller.managedPrefix(first.toFile()))
        assertEquals(prefix.toRealPath().toFile(), HeadlessBundleInstaller.managedPrefix(oldBundle))
        assertTrue(Files.isSymbolicLink(prefix.resolve("current")))
        assertEquals('a'.toString(), File(oldBundle, "lib/sdk.jar").readText())
        assertLauncher(prefix, root, 'a')
        HeadlessBundleInstaller.acquireRuntimeLease(oldBundle).use {
            val upgraded = execute("upgrade-bundle", prefix, second)
            assertNotEquals(oldBundle, upgraded.bundle?.directory)
            assertEquals("a", File(oldBundle, "lib/sdk.jar").readText(), "a live JVM keeps its immutable classpath")
            assertLauncher(prefix, root, 'b')
            assertFailsWith<IllegalStateException> { execute("uninstall-bundle", prefix, second) }
            assertTrue(Files.exists(prefix.resolve("current")))
        }
        execute("uninstall-bundle", prefix, second)
        assertFalse(Files.exists(prefix))
        assertEquals("external-user-data", Files.readString(data.resolve("credentials.properties")))
    }

    @Test
    fun `runtime leases work when installation lock files are read only`() = workspace { root ->
        val source = distribution(root.resolve("source"), 'a')
        val prefix = root.resolve("installed")
        val bundle = assertNotNull(execute("install-bundle", prefix, source).bundle).directory
        val locks = listOf(prefix.resolve(".install.lock"), bundle.toPath().parent.resolve(".runtime.lock"))
        val previous = locks.associateWith { Files.getPosixFilePermissions(it) }
        try {
            locks.forEach { Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("r--------")) }
            HeadlessBundleInstaller.acquireRuntimeLease(bundle).use { assertTrue(bundle.isDirectory) }
        } finally {
            previous.forEach { (path, permissions) -> Files.setPosixFilePermissions(path, permissions) }
        }
        execute("uninstall-bundle", prefix, source)
    }

    @Test
    fun `corrupt checksums unknown files traversal and symlinks fail before taking a prefix`() = workspace { root ->
        fun rejected(name: String, alter: (Path) -> Unit) {
            val source = distribution(root.resolve(name), 'a')
            alter(source)
            val prefix = root.resolve("prefix-$name")
            assertFails { execute("install-bundle", prefix, source) }
            assertFalse(Files.exists(prefix))
        }
        rejected("corrupt") { Files.writeString(it.resolve("lib/sdk.jar"), "changed") }
        rejected("unknown") { Files.writeString(it.resolve("user-file.txt"), "not managed") }
        rejected("empty-directory") { Files.createDirectory(it.resolve("unknown")) }
        rejected("symlink") {
            Files.delete(it.resolve("lib/sdk.jar"))
            Files.createSymbolicLink(it.resolve("lib/sdk.jar"), Path.of("../../outside"))
        }
        rejected("traversal") {
            Files.writeString(it.resolve("SHA256SUMS"), "${"0".repeat(64)}  ../outside\n")
        }
        rejected("duplicate") {
            val lines = Files.readAllLines(it.resolve("SHA256SUMS"))
            Files.writeString(it.resolve("SHA256SUMS"), (lines + lines.last()).joinToString("\n", postfix = "\n"))
        }
    }

    @Test
    fun `uninstall refuses mixed or modified installation without deleting anything`() = workspace { root ->
        val source = distribution(root.resolve("source"), 'a')
        val prefix = root.resolve("prefix")
        val bundle = assertNotNull(execute("install-bundle", prefix, source).bundle).directory.toPath()
        Files.writeString(prefix.resolve("notes.txt"), "user notes")
        assertFails { execute("uninstall-bundle", prefix, source) }
        assertTrue(Files.exists(bundle.resolve("lib/sdk.jar")))
        assertEquals("user notes", Files.readString(prefix.resolve("notes.txt")))
        Files.delete(prefix.resolve("notes.txt"))
        val jar = bundle.resolve("lib/sdk.jar")
        Files.writeString(jar, "modified installation")
        assertFails { execute("uninstall-bundle", prefix, source) }
        assertTrue(Files.exists(prefix.resolve("current")))
        assertEquals("modified installation", Files.readString(jar))
        Files.writeString(jar, "a")
        Files.writeString(prefix.resolve("bin/tt"), "unknown wrapper")
        assertFails { execute("uninstall-bundle", prefix, source) }
        assertTrue(Files.exists(jar))
    }

    @Test
    fun `upgrade reclaims only planned incomplete staging and can select an already published version`() = workspace { root ->
        val first = distribution(root.resolve("first"), 'a')
        val second = distribution(root.resolve("second"), 'b')
        val prefix = root.resolve("prefix")
        execute("install-bundle", prefix, first)
        val stage = Files.createDirectory(prefix.resolve(".staging"))
        Files.copy(second.resolve("SHA256SUMS"), stage.resolve(".bundle-checksums"))
        Files.createDirectories(stage.resolve("bundle/lib"))
        Files.writeString(stage.resolve("bundle/lib/sdk.jar"), "interrupted partial copy")
        assertFails { execute("uninstall-bundle", prefix, first) }
        val upgraded = execute("upgrade-bundle", prefix, second)
        assertFalse(Files.exists(stage))
        assertLauncher(prefix, root, 'b')
        // A crash after version publication but before current replacement leaves a harmless immutable version.
        Files.delete(prefix.resolve("current"))
        val old = HeadlessBundleInstaller.verifyBundle(first.toFile()).checksumSha256
        Files.createSymbolicLink(prefix.resolve("current"), Path.of("versions/$old/bundle"))
        val repeated = execute("upgrade-bundle", prefix, second)
        assertEquals(upgraded.bundle?.directory, repeated.bundle?.directory)
        assertEquals(2, Files.list(prefix.resolve("versions")).use { it.count().toInt() })
        assertLauncher(prefix, root, 'b')
    }

    @Test
    fun `mixed staging unknown prefix self uninstall and dangerous roots are rejected`() = workspace { root ->
        val source = distribution(root.resolve("source"), 'a')
        val prefix = root.resolve("prefix")
        val installed = execute("install-bundle", prefix, source)
        assertFails { execute("uninstall-bundle", prefix, assertNotNull(installed.bundle).directory.toPath()) }
        val stage = Files.createDirectory(prefix.resolve(".staging"))
        Files.copy(source.resolve("SHA256SUMS"), stage.resolve(".bundle-checksums"))
        Files.writeString(stage.resolve("personal.txt"), "keep me")
        assertFails { execute("upgrade-bundle", prefix, source) }
        assertEquals("keep me", Files.readString(stage.resolve("personal.txt")))
        assertTrue(Files.exists(prefix.resolve("current")))
        val unknown = Files.createDirectory(root.resolve("unknown"))
        Files.writeString(unknown.resolve("existing"), "keep")
        assertFails { execute("install-bundle", unknown, source) }
        assertEquals(setOf("existing"), Files.list(unknown).use { it.map { p -> p.fileName.toString() }.toList().toSet() })
        assertFails { execute("install-bundle", Path.of(System.getProperty("user.home")), source) }
        assertFails { execute("install-bundle", Path.of("/"), source) }
        val alias = root.resolve("alias")
        Files.createSymbolicLink(alias, prefix)
        assertFails { execute("uninstall-bundle", alias, source) }
    }

    @Test
    fun `three process shared leases allow concurrent launch and reject external uninstall until all exit`() = workspace { root ->
        val source = distribution(root.resolve("source"), 'a')
        val prefix = root.resolve("prefix")
        val bundle = assertNotNull(execute("install-bundle", prefix, source).bundle).directory
        val processes = mutableListOf<Process>()
        try {
            repeat(3) {
                val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
                    "-cp", childClasspath(), HeadlessBundleLeaseProbe::class.java.name, bundle.absolutePath)
                    .redirectErrorStream(true).start()
                processes += child
                val ready = java.util.concurrent.CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }
                assertEquals("READY", ready.get(15, TimeUnit.SECONDS))
            }
            assertFailsWith<IllegalStateException> { execute("uninstall-bundle", prefix, source) }
            processes.forEach { it.outputStream.close() }
            processes.forEach { assertTrue(it.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, it.exitValue()) }
            execute("uninstall-bundle", prefix, source)
            assertFalse(Files.exists(prefix))
        } finally {
            processes.filter(Process::isAlive).forEach { it.destroyForcibly(); it.waitFor(10, TimeUnit.SECONDS) }
        }
    }

    private fun childClasspath(): String = listOf(
        HeadlessBundleLeaseProbe::class.java, HeadlessBundleInstaller::class.java, kotlin.Unit::class.java,
    ).map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct().joinToString(File.pathSeparator)

    private fun distribution(path: Path, identity: Char): Path {
        Files.createDirectories(path.resolve("bin"))
        Files.createDirectories(path.resolve("lib"))
        Files.writeString(path.resolve("LICENSE"), "test license")
        Files.writeString(path.resolve("lib/sdk.jar"), identity.toString())
        Files.writeString(path.resolve("teamtalk-release.properties"), """
            artifactType=headless-distribution
            version=0.0.1
            buildIdentity=0.0.1+${identity.toString().repeat(40)}
            releaseBuildNumber=1
            protocolMajor=0
            protocolMinor=2
            minimumJavaVersion=21
        """.trimIndent() + "\n")
        listOf("tt-agent", "tt", "tt-mcp").forEach { entry ->
            Files.writeString(path.resolve("bin/$entry"), """#!/bin/sh
ROOT=${'$'}(CDPATH= cd -P -- "${'$'}(dirname -- "${'$'}0")/.." && pwd -P)
printf '%s\n' "${'$'}PWD"
cat "${'$'}ROOT/lib/sdk.jar"
""")
            Files.writeString(path.resolve("bin/$entry.bat"), "@echo $entry\r\n")
        }
        val paths = Files.walk(path).use { it.filter { file -> Files.isRegularFile(file, NOFOLLOW_LINKS) }.toList() }
        val sums = paths.map { path.relativize(it).joinToString("/") to hash(Files.readAllBytes(it)) }.sortedBy { it.first }
            .joinToString("\n", postfix = "\n") { (name, digest) -> "$digest  $name" }
        Files.writeString(path.resolve("SHA256SUMS"), sums)
        return path
    }
    private fun assertLauncher(prefix: Path, cwd: Path, expected: Char) {
        for (entry in listOf("tt-agent", "tt", "tt-mcp")) {
            val process = ProcessBuilder(prefix.resolve("bin/$entry").toString()).directory(cwd.toFile()).redirectErrorStream(true).start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals("$cwd\n$expected", process.inputStream.bufferedReader().readText())
        }
    }
    private fun execute(command: String, prefix: Path, source: Path) = HeadlessBundleInstaller.execute(command, listOf("--prefix", prefix.toString()), source.toFile())
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun workspace(block: (Path) -> Unit) {
        if (System.getProperty("os.name").startsWith("Windows", true)) return
        val root = Files.createTempDirectory("teamtalk-headless-install-").toRealPath()
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}

/** Separate JVMs verify real shared OS locks rather than overlapping locks in the test JVM. */
object HeadlessBundleLeaseProbe {
    @JvmStatic fun main(args: Array<String>) {
        HeadlessBundleInstaller.acquireRuntimeLease(File(args.single())).use {
            System.out.println("READY")
            while (System.`in`.read() >= 0) { }
        }
    }
}
