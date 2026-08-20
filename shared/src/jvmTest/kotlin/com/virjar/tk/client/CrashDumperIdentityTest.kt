package com.virjar.tk.client

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashDumperIdentityTest {
    @Test
    fun `account B cannot see account A crash and A relogin can process it`() {
        val dataDir = Files.createTempDirectory("teamtalk-crash-owner-").toFile()
        val uploads = mutableListOf<Pair<String, String>>()
        val transport = CrashUploadTransport { url, _, headers ->
            uploads += url to checkNotNull(headers["Authorization"])
            200
        }
        try {
            CrashDumper(dataDir, "https://a.example.test/", "uid-a", transport)
                .flushPending("account A crash")

            val accountB = CrashDumper(dataDir, "https://a.example.test", "uid-b", transport)
            assertFalse(accountB.hasPending())
            accountB.uploadPending("token-b")
            assertTrue(uploads.isEmpty())

            val accountARelogin = CrashDumper(dataDir, "https://a.example.test", "uid-a", transport)
            assertTrue(accountARelogin.hasPending())
            accountARelogin.uploadPending("token-a2")
            assertEquals(
                listOf("https://a.example.test/api/client-logs" to "Bearer token-a2"),
                uploads,
            )
            assertFalse(accountARelogin.hasPending())
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `same uid on another server and unowned crash stay isolated`() {
        val dataDir = Files.createTempDirectory("teamtalk-crash-server-").toFile()
        val uploads = mutableListOf<String>()
        val transport = CrashUploadTransport { url, _, _ ->
            uploads += url
            200
        }
        try {
            CrashDumper(dataDir, "https://one.example.test", "uid-a", transport).flushPending("server one")
            CrashDumper(dataDir).flushPending("pre-login crash")

            val otherServer = CrashDumper(dataDir, "https://two.example.test", "uid-a", transport)
            assertFalse(otherServer.hasPending())
            otherServer.uploadPending("token")

            val accountOnServerOne = CrashDumper(dataDir, "https://one.example.test", "uid-a", transport)
            accountOnServerOne.uploadPending("token")
            assertEquals(listOf("https://one.example.test/api/client-logs"), uploads)

            // Authenticated uploaders never enumerate the unowned namespace.
            assertTrue(CrashDumper(dataDir).hasPending())
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `crash namespace and atomic payload are owner private`() {
        val dataDir = Files.createTempDirectory("teamtalk-crash-mode-").toFile()
        try {
            CrashDumper(dataDir, "https://private.example.test", "uid-a")
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
            val dumper = CrashDumper(dataDir, "https://private.example.test", "uid-a")
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
            val dumper = CrashDumper(dataDir, "https://private.example.test", "uid-a")
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
}
