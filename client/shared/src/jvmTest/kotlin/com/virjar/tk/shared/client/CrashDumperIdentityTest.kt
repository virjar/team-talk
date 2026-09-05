package com.virjar.tk.shared.client

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashDumperIdentityTest {
    @Test
    fun `account B cannot see account A crash and A relogin can consume it`() {
        val dataDir = Files.createTempDirectory("teamtalk-crash-owner-").toFile()
        try {
            CrashDumper(dataDir, deployment("https://a.example.test/"), TEST_SYNC_DATASET_ID, "uid-a")
                .flushPending("account A crash")

            val accountB = CrashDumper(
                dataDir,
                deployment("https://a.example.test"),
                TEST_SYNC_DATASET_ID,
                "uid-b",
            )
            assertFalse(accountB.hasPending())
            assertEquals(null, accountB.pendingContent())

            val accountARelogin = CrashDumper(
                dataDir,
                deployment("https://a.example.test"),
                TEST_SYNC_DATASET_ID,
                "uid-a",
            )
            assertTrue(accountARelogin.hasPending())
            assertEquals("account A crash", accountARelogin.pendingContent())
            accountARelogin.markPendingUploaded("account A crash")
            assertFalse(accountARelogin.hasPending())
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `same uid on another server and unowned crash stay isolated`() {
        val dataDir = Files.createTempDirectory("teamtalk-crash-server-").toFile()
        try {
            CrashDumper(
                dataDir,
                deployment("https://one.example.test"),
                TEST_SYNC_DATASET_ID,
                "uid-a",
            )
                .flushPending("server one")
            CrashDumper(dataDir).flushPending("pre-login crash")

            val otherServer = CrashDumper(
                dataDir,
                deployment("https://two.example.test"),
                TEST_SYNC_DATASET_ID,
                "uid-a",
            )
            assertFalse(otherServer.hasPending())
            assertEquals(null, otherServer.pendingContent())

            val accountOnServerOne = CrashDumper(
                dataDir,
                deployment("https://one.example.test"),
                TEST_SYNC_DATASET_ID,
                "uid-a",
            )
            assertEquals("server one", accountOnServerOne.pendingContent())
            accountOnServerOne.markPendingUploaded("server one")
            assertFalse(accountOnServerOne.hasPending())

            // 已认证的上传方从不枚举无主命名空间。
            assertTrue(CrashDumper(dataDir).hasPending())
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `same HTTP base on a different TCP deployment cannot see pending crash`() {
        val dataDir = Files.createTempDirectory("teamtalk-crash-deployment-").toFile()
        try {
            val first = deployment("https://shared-files.example.test", tcpPort = 5100)
            val second = deployment("https://shared-files.example.test", tcpPort = 5200)
            CrashDumper(dataDir, first, TEST_SYNC_DATASET_ID, "uid-a").flushPending("deployment A")

            assertFalse(CrashDumper(dataDir, second, TEST_SYNC_DATASET_ID, "uid-a").hasPending())
            assertTrue(CrashDumper(dataDir, first, TEST_SYNC_DATASET_ID, "uid-a").hasPending())
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `same deployment account on a replacement dataset cannot consume old crash`() {
        val dataDir = Files.createTempDirectory("teamtalk-crash-dataset-").toFile()
        try {
            val deployment = deployment("https://dataset.example.test")
            CrashDumper(dataDir, deployment, TEST_SYNC_DATASET_ID, "uid-a")
                .flushPending("old dataset crash")

            val replacement = CrashDumper(
                dataDir,
                deployment,
                OTHER_TEST_SYNC_DATASET_ID,
                "uid-a",
            )
            assertFalse(replacement.hasPending())
            assertEquals(null, replacement.pendingContent())
            assertTrue(CrashDumper(dataDir, deployment, TEST_SYNC_DATASET_ID, "uid-a").hasPending())
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `crash namespace and atomic payload are owner private`() {
        val dataDir = Files.createTempDirectory("teamtalk-crash-mode-").toFile()
        try {
            CrashDumper(
                dataDir,
                deployment("https://private.example.test"),
                TEST_SYNC_DATASET_ID,
                "uid-a",
            )
                .flushPending("private crash")

            val pending = findPending(dataDir.toPath())
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(pending),
            )
            var directory = pending.parent
            while (directory != dataDir.toPath()) {
                assertEquals(
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(directory),
                )
                directory = directory.parent
            }
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `symlink pending payload is rejected without overwriting its target`() {
        val dataDir = Files.createTempDirectory("teamtalk-crash-symlink-").toFile()
        try {
            val dumper = CrashDumper(
                dataDir,
                deployment("https://private.example.test"),
                TEST_SYNC_DATASET_ID,
                "uid-a",
            )
            dumper.flushPending("first crash")
            val pending = findPending(dataDir.toPath())
            Files.delete(pending)
            val victim = dataDir.toPath().resolve("victim.log")
            Files.writeString(victim, "must survive")
            Files.createSymbolicLink(pending, victim)

            dumper.flushPending("attacker selected content")

            assertEquals("must survive", Files.readString(victim))
            assertFalse(dumper.hasPending())
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `hard linked payload and overwide namespace both fail closed`() {
        val dataDir = Files.createTempDirectory("teamtalk-crash-hardlink-").toFile()
        try {
            val dumper = CrashDumper(
                dataDir,
                deployment("https://private.example.test"),
                TEST_SYNC_DATASET_ID,
                "uid-a",
            )
            dumper.flushPending("first crash")
            val pending = findPending(dataDir.toPath())
            val linked = dataDir.toPath().resolve("linked-crash.log")
            Files.createLink(linked, pending)

            dumper.flushPending("must not replace hard link")

            assertEquals("first crash", Files.readString(linked))
            assertFalse(dumper.hasPending())

            Files.delete(linked)
            Files.delete(pending)
            val namespace = pending.parent
            val overwide = PosixFilePermissions.fromString("rwxr-xr-x")
            Files.setPosixFilePermissions(namespace, overwide)

            dumper.flushPending("must not enter broad directory")

            assertEquals(overwide, Files.getPosixFilePermissions(namespace))
            assertFalse(Files.exists(pending))
        } finally {
            dataDir.deleteRecursively()
        }
    }

    private fun findPending(dataDir: Path): Path = Files.walk(dataDir).use { paths ->
        paths.filter { it.fileName.toString() == "pending-crash.log" }
            .findFirst()
            .orElseThrow { AssertionError("pending crash was not created") }
    }

    private fun deployment(
        serverUrl: String,
        tcpPort: Int = 5100,
    ): DeploymentIdentity = DeploymentIdentity.from(
        tcpHost = java.net.URI(serverUrl).host,
        tcpPort = tcpPort,
        serverUrl = serverUrl,
    )
}
