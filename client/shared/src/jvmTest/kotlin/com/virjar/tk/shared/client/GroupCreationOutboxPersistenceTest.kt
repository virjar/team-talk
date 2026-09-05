package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.User
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupCreationOutboxPersistenceTest {
    @Test
    fun `single command survives restart and replacement is conditionally acknowledged`() {
        val root = createTempDirectory("group-create-outbox-").toFile()
        val database = root.resolve("cache.db")
        try {
            val first = open(database, createSchema = true)
            first.replacePendingGroupCreation(firstCommand)
            first.close()

            val reopened = open(database)
            assertEquals(firstCommand, reopened.getPendingGroupCreation())
            reopened.replacePendingGroupCreation(secondCommand)
            assertFalse(reopened.clearPendingGroupCreation(firstCommand.operationId))
            assertEquals(secondCommand, reopened.getPendingGroupCreation())
            reopened.close()

            val replaced = open(database)
            assertEquals(secondCommand, replaced.getPendingGroupCreation())
            assertTrue(replaced.clearPendingGroupCreation(secondCommand.operationId))
            replaced.close()

            val completed = open(database)
            assertNull(completed.getPendingGroupCreation())
            completed.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `server projection reset preserves the local reliable group command`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.replacePendingGroupCreation(firstCommand)

        cache.resetServerProjection(TEST_SYNC_DATASET_ID)

        assertEquals(firstCommand, cache.getPendingGroupCreation())
        cache.close()
    }

    @Test
    fun `corrupt command poisons only its slot and cannot be overwritten`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val database = AppDatabase(driver)
        database.appDatabaseQueries.upsertPendingGroupCreation(
            "00000000-0000-4000-8000-000000000073",
            "creator",
            "损坏但结构可读的群",
            null,
            // SQL 约束接受这个值，但它不是规范排序的成员集合，并且
            // 遗漏了创建者。LocalCache 构造函数必须隔离而不是抹除这一事实。
            "member-b\nmember-a",
        )

        val cache = LocalCacheImpl(driver)
        val localUser = User(uid = "local-user", username = "local", name = "离线用户")
        cache.upsertUser(localUser)
        assertEquals(localUser, cache.getUser(localUser.uid))

        assertFailsWith<CorruptPendingGroupCreationException> {
            cache.getPendingGroupCreation()
        }
        assertFailsWith<CorruptPendingGroupCreationException> {
            cache.replacePendingGroupCreation(firstCommand)
        }
        assertFailsWith<CorruptPendingGroupCreationException> {
            cache.clearPendingGroupCreation("00000000-0000-4000-8000-000000000073")
        }
        assertEquals(
            "00000000-0000-4000-8000-000000000073",
            database.appDatabaseQueries.selectPendingGroupCreation().executeAsOne().operation_id,
        )
        cache.close()
    }

    @Test
    fun `desktop cache namespace isolates group commands by deployment and account`() {
        val dataDir = Files.createTempDirectory("group-create-namespaces-").toFile()
        val firstDeployment = DeploymentIdentity.from(
            tcpHost = "im-a.example.test",
            tcpPort = 5100,
            serverUrl = "https://files-a.example.test/api",
        )
        val secondDeployment = DeploymentIdentity.from(
            tcpHost = "im-b.example.test",
            tcpPort = 5100,
            serverUrl = "https://files-b.example.test/api",
        )
        try {
            createDesktopLocalCache(firstDeployment, TEST_SYNC_DATASET_ID, "user-a", dataDir).let { cache ->
                cache.replacePendingGroupCreation(accountCommand)
                cache.close()
            }

            createDesktopLocalCache(firstDeployment, TEST_SYNC_DATASET_ID, "user-b", dataDir).let { cache ->
                assertNull(cache.getPendingGroupCreation())
                cache.close()
            }
            createDesktopLocalCache(secondDeployment, TEST_SYNC_DATASET_ID, "user-a", dataDir).let { cache ->
                assertNull(cache.getPendingGroupCreation())
                cache.close()
            }
            createDesktopLocalCache(firstDeployment, TEST_SYNC_DATASET_ID, "user-a", dataDir).let { cache ->
                assertEquals(accountCommand, cache.getPendingGroupCreation())
                cache.close()
            }
        } finally {
            dataDir.deleteRecursively()
        }
    }

    private fun open(database: java.io.File, createSchema: Boolean = false): LocalCache {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
        if (createSchema) AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private companion object {
        val firstCommand = PendingGroupCreationCommand.create(
            operationId = "00000000-0000-4000-8000-000000000071",
            creatorUid = "creator",
            name = "第一个群",
            memberUids = listOf("member-a", "member-b"),
        )
        val secondCommand = PendingGroupCreationCommand.create(
            operationId = "00000000-0000-4000-8000-000000000072",
            creatorUid = "creator",
            name = "第二个群",
            memberUids = listOf("member-c"),
        )
        val accountCommand = PendingGroupCreationCommand.create(
            operationId = "00000000-0000-4000-8000-000000000074",
            creatorUid = "user-a",
            name = "账号隔离群",
            memberUids = listOf("member-a"),
        )
    }
}
