package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.User
import java.io.File
import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmLocalCacheRecoveryTest {
    private val deployment = DeploymentIdentity.from(
        tcpHost = "cache-recovery.test.example",
        tcpPort = 5100,
        serverUrl = "https://cache-recovery.test.example/api",
    )

    @Test
    fun `confirmed corruption retains exact account family and opens a clean replacement`() {
        val dataDir = Files.createTempDirectory("tk-jvm-cache-recovery-").toFile()
        try {
            createDesktopLocalCache(deployment, TEST_SYNC_DATASET_ID, "damaged", dataDir).useCache { cache ->
                cache.upsertUser(User(uid = "lost-projection", username = "lost", name = "Lost"))
            }
            createDesktopLocalCache(deployment, TEST_SYNC_DATASET_ID, "healthy", dataDir).useCache { cache ->
                cache.upsertUser(User(uid = "healthy-projection", username = "healthy", name = "Healthy"))
            }

            val damagedFile = cacheFile(dataDir, "damaged")
            val corruptedBytes = "not-a-sqlite-database".encodeToByteArray()
            damagedFile.writeBytes(corruptedBytes)

            createDesktopLocalCache(deployment, TEST_SYNC_DATASET_ID, "damaged", dataDir).useCache { replacement ->
                assertNull(replacement.getUser("lost-projection"))
                replacement.upsertUser(User(uid = "rebuilt", username = "rebuilt", name = "Rebuilt"))
            }

            val quarantinedUserDirectory = damagedFile.parentFile.parentFile.listFiles().orEmpty()
                .single { candidate ->
                    candidate.name.startsWith("damaged.corrupt-") &&
                        candidate.name.substringAfter(".corrupt-").all { it.isLetterOrDigit() || it == '-' }
            }
            val quarantinedDatabase = File(quarantinedUserDirectory, localCacheDatabaseFileName())
            assertContentEquals(corruptedBytes, quarantinedDatabase.readBytes())
            assertTrue(damagedFile.isFile)

            createDesktopLocalCache(deployment, TEST_SYNC_DATASET_ID, "damaged", dataDir).useCache { reopened ->
                assertNotNull(reopened.getUser("rebuilt"))
            }
            createDesktopLocalCache(deployment, TEST_SYNC_DATASET_ID, "healthy", dataDir).useCache { healthy ->
                assertNotNull(healthy.getUser("healthy-projection"))
                assertNull(healthy.getUser("rebuilt"))
            }
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `quarantine atomically moves only one complete user namespace`() {
        val directory = Files.createTempDirectory("tk-jvm-cache-quarantine-").toFile()
        try {
            val usersDirectory = File(directory, "users").apply { mkdir() }
            val userDirectory = File(usersDirectory, "target").apply { mkdir() }
            val neighborDirectory = File(usersDirectory, "neighbor").apply { mkdir() }
            val database = File(userDirectory, "cache.db").withText("main")
            val wal = File(database.path + "-wal").withText("wal")
            val shm = File(database.path + "-shm").withText("shm")
            val journal = File(database.path + "-journal").withText("journal")
            val integrityMetadata = File(database.path + ".integrity-checked").withText("checked")
            val openMetadata = File(database.path + ".open").withText("open")
            File(neighborDirectory, "keep").withText("neighbor")

            val quarantine = quarantineJvmLocalCacheUserDirectory(
                userDirectory = userDirectory,
                quarantineId = "test",
            )

            assertFalse(userDirectory.exists())
            assertEquals(File(usersDirectory, "target.corrupt-test"), quarantine.quarantinedUserDirectory)
            assertEquals("main", File(quarantine.quarantinedUserDirectory, database.name).readText())
            assertEquals("wal", File(quarantine.quarantinedUserDirectory, wal.name).readText())
            assertEquals("shm", File(quarantine.quarantinedUserDirectory, shm.name).readText())
            assertEquals("journal", File(quarantine.quarantinedUserDirectory, journal.name).readText())
            assertEquals("checked", File(quarantine.quarantinedUserDirectory, integrityMetadata.name).readText())
            assertEquals("open", File(quarantine.quarantinedUserDirectory, openMetadata.name).readText())
            assertEquals("neighbor", File(neighborDirectory, "keep").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `headless policy preserves a corrupt reliable cache in place`() {
        val dataDir = Files.createTempDirectory("tk-jvm-cache-headless-usage-").toFile()
        try {
            createJvmLocalCache(
                deploymentIdentity = deployment,
                datasetId = TEST_SYNC_DATASET_ID,
                uid = "bot-owner",
                dataDir = dataDir,
                corruptionPolicy = JvmLocalCacheCorruptionPolicy.FAIL_PRESERVING,
            ).useCache { cache ->
                cache.upsertUser(User(uid = "reliable", username = "reliable", name = "Reliable"))
            }
            val database = cacheFile(dataDir, "bot-owner")
            val corruptedBytes = "reliable-cache-corruption".encodeToByteArray()
            database.writeBytes(corruptedBytes)

            val failure = kotlin.runCatching {
                createJvmLocalCache(
                    deploymentIdentity = deployment,
                    datasetId = TEST_SYNC_DATASET_ID,
                    uid = "bot-owner",
                    dataDir = dataDir,
                    corruptionPolicy = JvmLocalCacheCorruptionPolicy.FAIL_PRESERVING,
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertContentEquals(corruptedBytes, database.readBytes())
            assertTrue(
                database.parentFile.parentFile.listFiles().orEmpty()
                    .none { it.name.startsWith("bot-owner.corrupt-") },
            )
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `existing quarantine prevents an unbounded second retained copy`() {
        val directory = Files.createTempDirectory("tk-jvm-cache-quarantine-limit-").toFile()
        try {
            val usersDirectory = File(directory, "users").apply { mkdir() }
            val userDirectory = File(usersDirectory, "target").apply { mkdir() }
            File(userDirectory, "cache.db").withText("main")
            File(usersDirectory, "target.corrupt-existing").apply { mkdir() }

            val failure = kotlin.runCatching {
                quarantineJvmLocalCacheUserDirectory(userDirectory, "next")
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("main", File(userDirectory, "cache.db").readText())
            assertFalse(File(usersDirectory, "target.corrupt-next").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `only sqlite corruption result codes authorize quarantine`() {
        assertTrue(SQLException("corrupt", "", 11).hasJvmSqliteCorruptionCause())
        assertTrue(SQLException("not a db", "", 26).hasJvmSqliteCorruptionCause())
        assertTrue(
            IllegalStateException(
                "wrapped",
                SQLException("corrupt index", "", 11 or (3 shl 8)),
            ).hasJvmSqliteCorruptionCause(),
        )
        assertFalse(SQLException("busy", "", 5).hasJvmSqliteCorruptionCause())
        assertFalse(IllegalStateException("ordinary failure").hasJvmSqliteCorruptionCause())
    }

    private fun cacheFile(dataDir: File, uid: String): File = File(
        dataDir,
        "deployments/${deployment.fingerprint}/datasets/$TEST_SYNC_DATASET_ID/users/$uid/" +
            localCacheDatabaseFileName(),
    )

    private fun File.withText(value: String): File = apply { writeText(value) }

    private inline fun <T> LocalCache.useCache(block: (LocalCache) -> T): T = try {
        block(this)
    } finally {
        close()
    }
}
