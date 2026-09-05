package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Attachment
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupFileCommandOutboxPersistenceTest {
    @Test
    fun `all command kinds survive restart with complete immutable payloads`() {
        val root = createTempDirectory("group-file-command-outbox-").toFile()
        val database = root.resolve("cache.db")
        val commands = listOf(folder(), file(), version(), rename(), delete())
        try {
            open(database, createSchema = true).let { cache ->
                commands.forEach { assertEquals(it, cache.preparePendingGroupFileCommand(it)) }
                cache.close()
            }

            open(database).let { cache ->
                assertEquals(
                    commands.first(),
                    cache.preparePendingGroupFileCommand(
                        folder(commandId = uuid(50), entryId = uuid(51), createdAt = 99L),
                    ),
                    "restart must reuse the durable create identities for the same semantic intent",
                )
                assertEquals(commands, cache.getPendingGroupFileCommands())
                cache.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same intent reuses durable identities while different nearby intents remain independent`() {
        val cache = memoryCache()
        try {
            val original = folder()
            assertEquals(original, cache.preparePendingGroupFileCommand(original))
            val regenerated = folder(
                commandId = uuid(10),
                entryId = uuid(11),
                createdAt = 99L,
            )
            assertEquals(original, cache.preparePendingGroupFileCommand(regenerated))

            val sibling = folder(
                commandId = uuid(12),
                entryId = uuid(13),
                name = "项目资料二",
                createdAt = 2L,
            )
            assertEquals(sibling, cache.preparePendingGroupFileCommand(sibling))
            assertEquals(listOf(original, sibling), cache.getPendingGroupFileCommands())

            assertFailsWith<PendingGroupFileCommandConflictException> {
                cache.preparePendingGroupFileCommand(
                    file(
                        commandId = uuid(14),
                        entryId = uuid(15),
                        name = original.name!!,
                    ),
                )
            }
            assertEquals(listOf(original, sibling), cache.getPendingGroupFileCommands())
        } finally {
            cache.close()
        }
    }

    @Test
    fun `resource mutations retain one exact target generation without overwriting ambiguous payloads`() {
        val cache = memoryCache()
        try {
            val rename = cache.preparePendingGroupFileCommand(rename())
            assertEquals(
                rename,
                cache.preparePendingGroupFileCommand(rename(commandId = uuid(20), createdAt = 99L)),
            )
            assertFailsWith<PendingGroupFileCommandConflictException> {
                cache.preparePendingGroupFileCommand(
                    rename(commandId = uuid(21), name = "另一名称", createdAt = 100L),
                )
            }

            assertFailsWith<PendingGroupFileCommandConflictException> {
                cache.preparePendingGroupFileCommand(delete(entryId = rename.entryId))
            }

            val independentDelete = cache.preparePendingGroupFileCommand(delete())
            assertEquals(listOf(rename, independentDelete), cache.getPendingGroupFileCommands())
        } finally {
            cache.close()
        }
    }

    @Test
    fun `late acknowledgement cannot clear a newer generation`() {
        val cache = memoryCache()
        try {
            val old = cache.preparePendingGroupFileCommand(folder())
            assertTrue(cache.clearPendingGroupFileCommand(old.commandId))
            val replacement = cache.preparePendingGroupFileCommand(
                folder(commandId = uuid(20), entryId = uuid(21), createdAt = 2L),
            )

            assertFalse(cache.clearPendingGroupFileCommand(old.commandId))
            assertEquals(listOf(replacement), cache.getPendingGroupFileCommands())
            assertTrue(cache.clearPendingGroupFileCommand(replacement.commandId))
        } finally {
            cache.close()
        }
    }

    @Test
    fun `count and byte admission reject without evicting durable commands`() {
        val countDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(countDriver)
        val countCache = LocalCacheImpl(
            countDriver,
            DEFAULT_LOCAL_OUTBOX_LIMITS.copy(groupFileCommandCount = 1),
        )
        try {
            val first = countCache.preparePendingGroupFileCommand(folder())
            val failure = assertFailsWith<LocalOutboxCapacityExceededException> {
                countCache.preparePendingGroupFileCommand(
                    folder(commandId = uuid(30), entryId = uuid(31), name = "另一个目录"),
                )
            }
            assertEquals(LocalOutboxKind.GROUP_FILE_COMMAND, failure.outbox)
            assertEquals(LocalOutboxCapacityDimension.ENTRY_COUNT, failure.dimension)
            assertEquals(listOf(first), countCache.getPendingGroupFileCommands())
        } finally {
            countCache.close()
        }

        val first = folder()
        val byteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(byteDriver)
        val byteCache = LocalCacheImpl(
            byteDriver,
            DEFAULT_LOCAL_OUTBOX_LIMITS.copy(groupFileCommandBytes = first.payloadBytes),
        )
        try {
            byteCache.preparePendingGroupFileCommand(first)
            val failure = assertFailsWith<LocalOutboxCapacityExceededException> {
                byteCache.preparePendingGroupFileCommand(
                    folder(commandId = uuid(32), entryId = uuid(33), name = "另一个目录"),
                )
            }
            assertEquals(LocalOutboxCapacityDimension.STORED_BYTES, failure.dimension)
            assertEquals(listOf(first), byteCache.getPendingGroupFileCommands())
        } finally {
            byteCache.close()
        }
    }

    @Test
    fun `projection reset preserves group-file commands`() {
        val cache = memoryCache()
        try {
            val pending = cache.preparePendingGroupFileCommand(file())
            cache.resetServerProjection(TEST_SYNC_DATASET_ID)
            assertEquals(listOf(pending), cache.getPendingGroupFileCommands())
        } finally {
            cache.close()
        }
    }

    @Test
    fun `desktop namespace isolates commands by account and deployment`() {
        val dataDir = Files.createTempDirectory("group-file-command-namespaces-").toFile()
        val deploymentA = DeploymentIdentity.from("im-a.example.test", 5100, "https://a.example.test")
        val deploymentB = DeploymentIdentity.from("im-b.example.test", 5100, "https://b.example.test")
        try {
            createDesktopLocalCache(deploymentA, TEST_SYNC_DATASET_ID, "user-a", dataDir).let { cache ->
                cache.preparePendingGroupFileCommand(folder())
                cache.close()
            }
            createDesktopLocalCache(deploymentA, TEST_SYNC_DATASET_ID, "user-b", dataDir).let { cache ->
                assertTrue(cache.getPendingGroupFileCommands().isEmpty())
                cache.close()
            }
            createDesktopLocalCache(deploymentB, TEST_SYNC_DATASET_ID, "user-a", dataDir).let { cache ->
                assertTrue(cache.getPendingGroupFileCommands().isEmpty())
                cache.close()
            }
            createDesktopLocalCache(deploymentA, TEST_SYNC_DATASET_ID, "user-a", dataDir).let { cache ->
                assertEquals(listOf(folder()), cache.getPendingGroupFileCommands())
                cache.close()
            }
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `corrupt payload accounting poisons only the group-file family`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val command = folder()
        AppDatabase(driver).appDatabaseQueries.insertPendingGroupFileCommand(
            command.commandId,
            command.intentKey,
            command.kind.code,
            command.entryId,
            command.chatId,
            command.parentId,
            command.name,
            null,
            null,
            null,
            null,
            null,
            command.createdAt,
            command.payloadBytes + 1L,
        )
        val cache = LocalCacheImpl(driver)
        try {
            assertIs<CorruptGroupFileCommandOutboxException>(
                assertFailsWith<IllegalStateException> { cache.getPendingGroupFileCommands() },
            )
            assertTrue(cache.getContacts().isEmpty())
            assertFailsWith<CorruptGroupFileCommandOutboxException> {
                cache.preparePendingGroupFileCommand(folder(commandId = uuid(40), entryId = uuid(41)))
            }
        } finally {
            cache.close()
        }
    }

    private fun memoryCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun open(database: java.io.File, createSchema: Boolean = false): LocalCache {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
        if (createSchema) AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun folder(
        commandId: String = uuid(1),
        entryId: String = uuid(2),
        name: String = "项目资料",
        createdAt: Long = 1L,
    ) = PendingGroupFileCommand.createFolder(
        commandId,
        entryId,
        CHAT_ID,
        PARENT_ID,
        name,
        createdAt,
    )

    private fun file(
        commandId: String = uuid(3),
        entryId: String = uuid(4),
        name: String = "设计稿.pdf",
        createdAt: Long = 2L,
    ) = PendingGroupFileCommand.createFile(
        commandId,
        entryId,
        CHAT_ID,
        PARENT_ID,
        name,
        ATTACHMENT,
        createdAt,
    )

    private fun version(
        entryId: String = uuid(40),
    ) = PendingGroupFileCommand.addVersion(
        commandId = uuid(5),
        chatId = CHAT_ID,
        entryId = entryId,
        attachment = ATTACHMENT.copy(path = "user/file-v2.pdf"),
        expectedRevision = 7L,
        createdAt = 3L,
    )

    private fun rename(
        commandId: String = uuid(6),
        entryId: String = uuid(41),
        name: String = "最终设计稿.pdf",
        createdAt: Long = 4L,
    ) = PendingGroupFileCommand.rename(
        commandId = commandId,
        chatId = CHAT_ID,
        parentId = PARENT_ID,
        entryId = entryId,
        name = name,
        expectedRevision = 8L,
        createdAt = createdAt,
    )

    private fun delete(
        entryId: String = uuid(42),
    ) = PendingGroupFileCommand.delete(
        commandId = uuid(7),
        chatId = CHAT_ID,
        parentId = PARENT_ID,
        entryId = entryId,
        expectedRevision = 9L,
        createdAt = 5L,
    )

    private fun uuid(suffix: Int): String = "00000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}"

    private companion object {
        const val CHAT_ID = "00000000-0000-4000-8000-000000000101"
        const val PARENT_ID = "00000000-0000-4000-8000-000000000102"
        val ATTACHMENT = Attachment("user/file.pdf", "file.pdf", "application/pdf", 138L)
    }
}
