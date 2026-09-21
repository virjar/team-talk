@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.client

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.platform.*
import kotlinx.cinterop.*
import co.touchlab.sqliter.sqlite3.*
import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import kotlin.test.*

class IosLocalCacheRecoveryIntegrationTest {
    private val deployment = DeploymentIdentity.from("127.0.0.1", 9000, "http://127.0.0.1:8080")

    @Test
    fun unversionedDatabaseIsRejectedWithoutChangingItsBytes() = withDataDirectory { root ->
        val dataset = platformRandomUuid()
        val file = databaseFile(root, dataset)
        sqlite(file, "CREATE TABLE unpublished_pending (payload TEXT); INSERT INTO unpublished_pending VALUES ('must survive');")
        val before = file.readBytes()
        assertFailsWith<IllegalStateException> { createIosLocalCache(deployment, dataset, "owner", root) }
        assertContentEquals(before, file.readBytes())
        assertFalse(PlatformFile(file.parentFile, ".cache-recovery").exists())
    }

    @Test
    fun newerSchemaIsRejectedAndConfirmedCorruptionCanRebuild() = withDataDirectory { root ->
        val dataset = platformRandomUuid()
        createIosLocalCache(deployment, dataset, "owner", root).close()
        val file = databaseFile(root, dataset)
        sqlite(file, "PRAGMA user_version=2147483647")
        val before = file.readBytes()
        assertFailsWith<IllegalStateException> { createIosLocalCache(deployment, dataset, "owner", root) }
        assertContentEquals(before, file.readBytes())

        file.writeBytes(ByteArray(256) { 0x37 })
        createIosLocalCache(deployment, dataset, "owner", root).close()
        assertTrue(file.length() > 256)
        assertFalse(PlatformFile(file.parentFile, ".cache-recovery").exists())
        assertTrue(checkNotNull(file.parentFile?.list()).none { it.startsWith(".cache-corrupt-") })
        // The replacement remains reopenable and owns the same account namespace.
        createIosLocalCache(deployment, dataset, "owner", root).close()
    }

    @Test
    fun schemaFiveMigratesWithDataIntactAndLocalHistoryFlagsSurviveReopen() = withDataDirectory { root ->
        // Fixed 5 -> 6 fixture: 5.sqm adds only conversation_local_flag, with its two local flags.
        // All older tables keep their current layout. Update this fixture explicitly if the schema grows.
        assertEquals(6L, AppDatabase.Schema.version)
        val dataset = platformRandomUuid()
        val file = databaseFile(root, dataset)
        val chatId = "migration-chat"
        val draft = "未发送的草稿 🌱"
        val original = Message(chatId = chatId, clientMsgId = "before-migration", serverSeq = 3L,
            senderUid = "sender", messageType = MessageType.RICH_TEXT.code, timestamp = 30L,
            body = buildRichTextBody("迁移前的消息"))
        val conversation = Conversation(chatId = chatId, chatType = 1, lastMessage = "迁移前的消息",
            lastMsgTimestamp = 30L, lastSeq = 3L, readSeq = 3L)
        val seed = createIosLocalCache(deployment, dataset, "owner", root)
        val draftGeneration = try {
            seed.upsertConversation(conversation)
            seed.insertMessage(original)
            seed.setConversationDraft(chatId, draft)
        } finally { seed.close() }
        sqlite(file, "BEGIN; DROP TABLE conversation_local_flag; PRAGMA user_version=5; COMMIT;")
        assertEquals(5L, sqliteScalar(file, "PRAGMA user_version"))
        assertEquals(0L, sqliteScalar(file,
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='conversation_local_flag'"))

        val migrated = createIosLocalCache(deployment, dataset, "owner", root)
        try {
            val preserved = migrated.getConversations().single()
            assertEquals(chatId, preserved.chatId)
            assertEquals(conversation.lastMessage, preserved.lastMessage)
            assertEquals(3L, preserved.lastSeq)
            assertEquals(3L, preserved.readSeq)
            assertEquals(draft, preserved.draft)
            assertEquals(PendingConversationDraft(chatId, draft, draftGeneration), migrated.getPendingConversationDraft(chatId))
            assertEquals(original.clientMsgId, migrated.getMessages(chatId).single().clientMsgId)
            assertContentEquals(ProtoCodec.encode(original), ProtoCodec.encode(migrated.getMessages(chatId).single()))
            migrated.clearChatHistory(chatId)
            migrated.setConversationMarkedUnread(chatId, true)
        } finally { migrated.close() }
        assertEquals(6L, sqliteScalar(file, "PRAGMA user_version"))

        val fresh = original.copy(clientMsgId = "after-clear", serverSeq = 4L, timestamp = 40L,
            body = buildRichTextBody("清空后的新消息"))
        val reopened = createIosLocalCache(deployment, dataset, "owner", root)
        try {
            val cleared = reopened.getConversations().single()
            assertEquals(3L, cleared.lastSeq)
            assertEquals(3L, cleared.readSeq)
            assertNull(cleared.lastMessage)
            assertEquals(1, cleared.unreadCount, "manual unread survives reopening an otherwise fully read conversation")
            assertTrue(reopened.getMessages(chatId).isEmpty())
            // Both a late live event and a fetched history page must obey the persisted clear watermark.
            reopened.insertMessage(original)
            val lease = reopened.beginMessageHistoryLease(chatId, resetResidentWindow = true)
            assertTrue(reopened.applyMessageHistoryPage(lease, listOf(original)))
            assertTrue(reopened.getMessages(chatId).isEmpty())
            reopened.insertMessage(fresh)
            reopened.upsertConversation(conversation.copy(lastMessage = "清空后的新消息", lastMsgTimestamp = 40L, lastSeq = 4L))
            assertEquals(listOf(fresh.clientMsgId), reopened.getMessages(chatId).map(Message::clientMsgId))
            assertEquals("清空后的新消息", reopened.getConversations().single().lastMessage)
            reopened.enqueueConversationRead(chatId, 4L)
            assertEquals(0, reopened.getConversations().single().unreadCount)
        } finally { reopened.close() }

        val verified = createIosLocalCache(deployment, dataset, "owner", root)
        try {
            assertEquals(0, verified.getConversations().single().unreadCount)
            assertEquals(draft, verified.getConversations().single().draft)
            assertEquals(PendingConversationDraft(chatId, draft, draftGeneration), verified.getPendingConversationDraft(chatId))
            assertEquals(fresh.clientMsgId, verified.getMessages(chatId).single().clientMsgId)
            assertContentEquals(ProtoCodec.encode(fresh), ProtoCodec.encode(verified.getMessages(chatId).single()))
        } finally { verified.close() }
    }

    private fun databaseFile(root: PlatformFile, dataset: String): PlatformFile = PlatformFile(
        iosPrivateDirectory(root, listOf("deployments", deployment.fingerprint, "datasets", dataset, "users", "owner")),
        localCacheDatabaseFileName(),
    )
    private fun sqlite(file: PlatformFile, sql: String) = memScoped {
        val pointer = alloc<CPointerVar<sqlite3>>()
        assertEquals(SQLITE_OK, sqlite3_open(file.path, pointer.ptr))
        try { assertEquals(SQLITE_OK, sqlite3_exec(pointer.value, sql, null, null, null)) }
        finally { sqlite3_close(pointer.value) }
    }
    private fun sqliteScalar(file: PlatformFile, sql: String): Long = memScoped {
        val database = alloc<CPointerVar<sqlite3>>()
        assertEquals(SQLITE_OK, sqlite3_open_v2(file.path, database.ptr, SQLITE_OPEN_READONLY, null))
        try {
            val statement = alloc<CPointerVar<sqlite3_stmt>>()
            assertEquals(SQLITE_OK, sqlite3_prepare_v2(database.value, sql.cstr.ptr, -1, statement.ptr, null))
            try {
                assertEquals(SQLITE_ROW, sqlite3_step(statement.value))
                sqlite3_column_int64(statement.value, 0)
            } finally { sqlite3_finalize(statement.value) }
        } finally { sqlite3_close(database.value) }
    }
    private fun withDataDirectory(test: (PlatformFile) -> Unit) {
        val root = PlatformFile(platformDataDir(), "native-cache-test-${platformRandomUuid()}")
        check(root.mkdir())
        try { test(root) } finally { check(root.deleteRecursively()) }
    }
}
