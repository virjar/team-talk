package com.virjar.tk.shared.client

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountDataCleanupTest {
    private val owner = AccountDataOwner("a".repeat(64), "00000000-0000-4000-8000-000000000001", "user1")

    @Test
    fun `interrupted cleanup resumes from durable owner and retains marker until credentials are cleared`() {
        val directory = createTempDirectory("account-cleanup-").toFile()
        try {
            val account = directory.resolve("users/user1").apply { mkdirs() }
            account.resolve("messages.db").writeText("private messages")
            val other = directory.resolve("users/user10").apply { mkdirs() }.resolve("messages.db")
            other.writeText("other account")
            var interrupted = true
            val cleanup = AccountDataCleanup(directory) {
                if (interrupted) error("simulated interrupted resource drain")
                listOf(AccountDataCleanupTarget.tree(directory, "users", it.uid))
            }
            assertFailsWith<IllegalStateException> { cleanup.deleteOwnedData(owner) }
            assertTrue(account.exists())
            cleanup.begin(owner)
            assertFailsWith<IllegalStateException> { cleanup.deleteOwnedData(owner) }
            assertEquals(listOf(owner), cleanup.pendingOwners())
            interrupted = false
            cleanup.deleteOwnedData(owner)
            assertFalse(account.exists())
            assertEquals("other account", other.readText())

            // A fresh process can finish the credential step even after all content has gone.
            val resumed = AccountDataCleanup(directory) {
                listOf(AccountDataCleanupTarget.tree(directory, "users", it.uid))
            }
            assertEquals(listOf(owner), resumed.pendingOwners())
            resumed.deleteOwnedData(owner)
            assertEquals(listOf(owner), resumed.pendingOwners())
            resumed.complete(owner)
            assertTrue(resumed.pendingOwners().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `deletion unlinks account leaf links but refuses an ancestor link and retains pending cleanup`() {
        val directory = createTempDirectory("account-cleanup-links-").toFile()
        val outside = createTempDirectory("account-cleanup-outside-").toFile()
        try {
            val privateFile = outside.resolve("keep").apply { writeText("outside") }
            Files.createSymbolicLink(directory.resolve("account").toPath(), outside.toPath())
            val leaf = AccountDataCleanup(directory) {
                listOf(AccountDataCleanupTarget.tree(directory, "account"))
            }
            leaf.begin(owner)
            leaf.deleteOwnedData(owner)
            assertFalse(Files.exists(directory.resolve("account").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("outside", privateFile.readText())

            Files.createSymbolicLink(directory.resolve("account").toPath(), outside.toPath())
            val ancestor = AccountDataCleanup(directory) {
                listOf(AccountDataCleanupTarget.tree(directory, "account", "keep"))
            }
            assertFailsWith<IllegalArgumentException> { ancestor.deleteOwnedData(owner) }
            assertEquals(listOf(owner), ancestor.pendingOwners())
            assertEquals("outside", privateFile.readText())
            Files.delete(directory.resolve("account").toPath())
        } finally {
            directory.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `Android database cleanup includes sidecars and quarantines but never sibling account or dataset`() {
        val directory = createTempDirectory("account-cleanup-db-").toFile()
        try {
            val databases = directory.resolve("databases").apply { mkdirs() }
            val base = localCacheDatabaseFileName(owner.deploymentFingerprint, owner.datasetId, owner.uid)
            val owned = listOf(
                base, "$base-wal", "$base-shm", "$base-journal", "$base.open", "$base.integrity-checked",
                "$base.corruption-reported", "$base.corrupt-1234", "$base.corrupt-1234-wal",
                "$base.corrupt-1234.integrity-checked",
            )
            val other = listOf(
                localCacheDatabaseFileName(owner.deploymentFingerprint, owner.datasetId, "user10"),
                localCacheDatabaseFileName(owner.deploymentFingerprint, "00000000-0000-4000-8000-000000000002", owner.uid),
                localCacheDatabaseFileName("b".repeat(64), owner.datasetId, owner.uid),
                "${base}backup",
            )
            (owned + other).forEach { databases.resolve(it).writeText(it) }
            val cleanup = AccountDataCleanup(directory) { listOf(accountAndroidDatabaseCleanupTarget(databases, it)) }
            cleanup.begin(owner)
            cleanup.deleteOwnedData(owner)
            assertEquals(other.toSet(), databases.listFiles()!!.map { it.name }.toSet())
            cleanup.complete(owner)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `malformed pending owner blocks recovery without deleting account data`() {
        val directory = createTempDirectory("account-cleanup-corrupt-").toFile()
        try {
            val data = directory.resolve("keep").apply { writeText("data") }
            val cleanup = AccountDataCleanup(directory) { listOf(AccountDataCleanupTarget.tree(directory, "keep")) }
            cleanup.begin(owner)
            val marker = directory.resolve(".account-cleanup").listFiles()!!.single { it.name.endsWith(".pending") }
            marker.writeText("account-ban-v1\ninvalid\n../../other\nuser1\n")
            assertFailsWith<IllegalArgumentException> { cleanup.pendingOwners() }
            assertEquals("data", data.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
