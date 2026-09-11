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
    fun `confirmed corruption reopens a clean replacement and deletes the corrupt family`() {
        val dataDir = Files.createTempDirectory("tk-jvm-cache-recovery-").toFile()
        try {
            createDesktopLocalCache(deployment, TEST_SYNC_DATASET_ID, "damaged", dataDir).useCache { cache ->
                cache.upsertUser(User(uid = "lost-projection", username = "lost", name = "Lost"))
            }
            createDesktopLocalCache(deployment, TEST_SYNC_DATASET_ID, "healthy", dataDir).useCache { cache ->
                cache.upsertUser(User(uid = "healthy-projection", username = "healthy", name = "Healthy"))
            }

            val damagedFile = cacheFile(dataDir, "damaged")
            damagedFile.writeBytes("not-a-sqlite-database".encodeToByteArray())

            createDesktopLocalCache(deployment, TEST_SYNC_DATASET_ID, "damaged", dataDir).useCache { replacement ->
                assertNull(replacement.getUser("lost-projection"))
                replacement.upsertUser(User(uid = "rebuilt", username = "rebuilt", name = "Rebuilt"))
            }

            // 服务器是唯一可靠信息源：替换库验证健康后损坏族立即删除，不再保留待处置副本。
            assertTrue(damagedFile.isFile)
            damagedFile.parentFile.parentFile.listFiles().orEmpty()
                .filter { it.name.startsWith("damaged.corrupt-") }
                .forEach { assertFalse(it.exists(), "quarantine family must be deleted: ${it.name}") }

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
    fun `headless corrupt cache also rebuilds from the server`() {
        val dataDir = Files.createTempDirectory("tk-jvm-cache-headless-usage-").toFile()
        try {
            createJvmLocalCache(
                deploymentIdentity = deployment,
                datasetId = TEST_SYNC_DATASET_ID,
                uid = "bot-owner",
                dataDir = dataDir,
            ).useCache { cache ->
                cache.upsertUser(User(uid = "reliable", username = "reliable", name = "Reliable"))
            }
            val database = cacheFile(dataDir, "bot-owner")
            database.writeBytes("reliable-cache-corruption".encodeToByteArray())

            createJvmLocalCache(
                deploymentIdentity = deployment,
                datasetId = TEST_SYNC_DATASET_ID,
                uid = "bot-owner",
                dataDir = dataDir,
            ).useCache { rebuilt ->
                assertNull(rebuilt.getUser("reliable"))
                rebuilt.upsertUser(User(uid = "rebuilt", username = "rebuilt", name = "Rebuilt"))
            }

            createJvmLocalCache(
                deploymentIdentity = deployment,
                datasetId = TEST_SYNC_DATASET_ID,
                uid = "bot-owner",
                dataDir = dataDir,
            ).useCache { reopened ->
                assertNotNull(reopened.getUser("rebuilt"))
            }
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `leftover quarantine from an interrupted recovery is swept before the next one`() {
        val directory = Files.createTempDirectory("tk-jvm-cache-quarantine-sweep-").toFile()
        try {
            val usersDirectory = File(directory, "users").apply { mkdir() }
            val userDirectory = File(usersDirectory, "target").apply { mkdir() }
            File(userDirectory, "cache.db").withText("main")
            val leftover = File(usersDirectory, "target.corrupt-existing").apply { mkdir() }
            File(leftover, "cache.db").withText("stale")

            val quarantine = quarantineJvmLocalCacheUserDirectory(userDirectory, "next")

            assertFalse(leftover.exists(), "interrupted-recovery leftover must be swept")
            assertEquals("main", File(quarantine.quarantinedUserDirectory, "cache.db").readText())
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

    @Test
    fun `quarantine delete reports failure without throwing`() {
        val directory = Files.createTempDirectory("tk-jvm-cache-quarantine-delete-").toFile()
        try {
            val usersDirectory = File(directory, "users").apply { mkdir() }
            val userDirectory = File(usersDirectory, "target").apply { mkdir() }
            File(userDirectory, "cache.db").withText("main")
            val quarantine = quarantineJvmLocalCacheUserDirectory(userDirectory, "del")

            assertNull(deleteJvmLocalCacheQuarantine(quarantine))
            assertFalse(quarantine.quarantinedUserDirectory.exists())
        } finally {
            directory.deleteRecursively()
        }
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
