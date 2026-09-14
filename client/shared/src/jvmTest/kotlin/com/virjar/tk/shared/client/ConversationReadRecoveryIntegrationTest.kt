package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ConversationPage
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.MessageRpcContract
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.ConversationRepository
import com.virjar.tk.shared.repository.MessageRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual SQLite restart, generated RPC codecs, and explicitly ordered acknowledgements/events. */
class ConversationReadRecoveryIntegrationTest {
    @Test
    fun `ready reconciliation repairs persisted future reads and new unread survives another restart`() = runBlocking {
        withDatabase { file ->
            seedLegacyFutureRead(file)
            val cache = open(file)
            cache.bindSyncDataset(DATASET)
            val rpc = FakeRpcInvoker().apply {
                    enqueueOk(ProtoCodec.encode(ConversationPage(listOf(conversation(147, 40)), null)))
                    enqueueOk(ProtoCodec.encodeList(listOf(message(CHAT, 147))))
                }
            val conversations = ConversationRepository(rpc, cache)
            val client = ImClient()
            val events = EventProcessor(client, cache, ownerUid = "owner",
                onConversationsDirty = { conversations.listConversations().getOrThrow() })
            try {
                cache.insertMessage(message("another-chat", 500))
                cache.setConversationDraft(CHAT, "keep local draft")
                assertEquals(330L, cache.getConversations().single().readSeq)
                events.requireConversationReconciliation()
                assertTrue(events.refreshDirtyConversations(authenticated = true))
                val recovered = cache.getConversations().single()
                assertEquals(40L, recovered.readSeq)
                assertEquals(107, recovered.unreadCount)
                assertEquals("keep local draft", recovered.draft)
                assertTrue(cache.getPendingConversationReads().isEmpty())
                MessageRepository(rpc, cache).retryPendingReads().getOrThrow()
                assertEquals(2, rpc.calls.size, "only the snapshot and exact legacy message-head check are sent")

                events.processNotify(NotifyPayload(1, NotifyType.CONVERSATION_UPDATED.code,
                    ProtoCodec.encode(conversation(148, 40))))
                assertEquals(108, cache.getConversations().single().unreadCount)
            } finally {
                events.stop()
                client.destroy()
                cache.close()
            }
            open(file).let { reopened ->
                try {
                    assertEquals(40L, reopened.getConversations().single().readSeq)
                    assertEquals(108, reopened.getConversations().single().unreadCount)
                    assertTrue(reopened.getPendingConversationReads().isEmpty())
                    assertEquals("keep local draft", reopened.getPendingConversationDraft(CHAT)?.draft)
                } finally { reopened.close() }
            }
        }
    }

    @Test
    fun `another chats cursor and unconfirmed messages cannot admit a future read`() = runBlocking {
        withDatabase { file ->
            val cache = open(file)
            try {
                cache.upsertConversation(conversation(3, 1))
                cache.insertMessage(message("another-chat", 330))
                cache.insertMessage(message(CHAT, 0))
                val rpc = FakeRpcInvoker()
                var wakes = 0
                val repository = MessageRepository(rpc, cache, onPendingMirrorCommitted = { wakes++ })
                assertFailsWith<IllegalArgumentException> { repository.markReadLocal(CHAT, 10) }
                assertFailsWith<IllegalArgumentException> { repository.markReadLocal("unknown-chat", 1) }
                assertEquals(1L, cache.getConversations().single().readSeq)
                assertEquals(2, cache.getConversations().single().unreadCount)
                assertTrue(cache.getPendingConversationReads().isEmpty())
                assertEquals(0, wakes)
                assertTrue(rpc.calls.isEmpty())
                assertEquals(3L, repository.markReadLocal(CHAT, 3))
                assertEquals(1, wakes)
            } finally { cache.close() }
        }
    }

    @Test
    fun `confirmed history ahead of conversation preserves offline read through restart and older snapshot`() = runBlocking {
        withDatabase { file ->
            var cache = open(file)
            try {
                cache.upsertConversation(conversation(3, 1))
                val rpc = FakeRpcInvoker().apply {
                    enqueueOk(ProtoCodec.encodeList(listOf(message(CHAT, 10))))
                    enqueueError(504)
                }
                val repository = MessageRepository(rpc, cache)
                repository.getHistory(CHAT).getOrThrow()
                assertEquals(Outcome.Failure(AppError.Timeout), repository.markRead(CHAT, 10))
            } finally { cache.close() }
            cache = open(file)
            try {
                val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(ConversationPage(listOf(conversation(3, 1)), null))) }
                ConversationRepository(rpc, cache).listConversations().getOrThrow()
                assertEquals(10L, cache.getConversations().single().readSeq)
                assertEquals(PendingConversationRead(CHAT, 10), cache.getPendingConversationRead(CHAT))
            } finally { cache.close() }
        }
    }

    @Test
    fun `old acknowledgement cannot clear a newer confirmed offline read`() = runBlocking {
        withDatabase { file ->
            val cache = open(file)
            try {
                cache.upsertConversation(conversation(3, 1))
                cache.insertMessage(message(CHAT, 10))
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val payloads = mutableListOf<ByteArray?>()
                val rpc = object : RpcInvoker {
                    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                        assertEquals(MessageRpcContract.M_MARK_READ, methodId)
                        payloads += payload
                        if (payloads.size == 1) { started.complete(Unit); release.await() }
                        return ResponsePayload(1, 0, null)
                    }
                }
                val repository = MessageRepository(rpc, cache)
                repository.markReadLocal(CHAT, 10)
                val first = async(start = CoroutineStart.UNDISPATCHED) { repository.mirrorRead(CHAT) }
                started.await()
                cache.insertMessage(message(CHAT, 12))
                repository.markReadLocal(CHAT, 12)
                release.complete(Unit)
                first.await().getOrThrow()
                assertEquals(PendingConversationRead(CHAT, 12), cache.getPendingConversationRead(CHAT))
                repository.retryPendingReads().getOrThrow()
                assertNull(cache.getPendingConversationRead(CHAT))
                assertContentEquals(MessageRpcContract.encodeMarkRead(CHAT, 10), payloads[0])
                assertContentEquals(MessageRpcContract.encodeMarkRead(CHAT, 12), payloads[1])
            } finally { cache.close() }
        }
    }

    @Test
    fun `checkpoint validates before removing messages and old replay cannot discard accepted reads`() = runBlocking {
        withDatabase { file ->
            val cache = open(file)
            try {
                cache.bindSyncDataset(DATASET)
                cache.upsertConversation(conversation(3, 1))
                cache.insertMessage(message(CHAT, 10))
                cache.enqueueConversationRead(CHAT, 10)
                cache.applyServerProjectionCheckpoint(DATASET, 0, checkpoint(conversation(3, 1)))
                assertTrue(cache.getMessages(CHAT).isEmpty())
                assertEquals(PendingConversationRead(CHAT, 10), cache.getPendingConversationRead(CHAT))
                cache.upsertConversation(conversation(4, 1))
                assertEquals(10L, cache.getConversations().single().readSeq)
                assertEquals(PendingConversationRead(CHAT, 10), cache.getPendingConversationRead(CHAT))
                // History reads RocksDB while the fresh conversation snapshot may still lag in PG.
                assertTrue(cache.applyConversationSnapshot(cache.beginConversationSnapshot(), listOf(conversation(3, 1))))
                assertEquals(10L, cache.getConversations().single().readSeq)
                assertEquals(PendingConversationRead(CHAT, 10), cache.getPendingConversationRead(CHAT))
                assertTrue(cache.applyConversationSnapshot(cache.beginConversationSnapshot(), listOf(conversation(10, 1))))
                assertEquals(10L, cache.getConversations().single().readSeq)
                assertEquals(PendingConversationRead(CHAT, 10), cache.getPendingConversationRead(CHAT))
            } finally { cache.close() }
        }
    }

    @Test
    fun `reset preserves orphan reads until authority and cannot reuse its old snapshot`() = runBlocking {
        withDatabase { file ->
            val cache = open(file)
            try {
                cache.insertMessage(message(CHAT, 10))
                cache.enqueueConversationRead(CHAT, 10)
                val beforeReset = cache.beginConversationSnapshot()
                cache.resetServerProjection(DATASET)
                assertFalse(cache.applyConversationSnapshot(beforeReset, listOf(conversation(3, 1))))
                assertTrue(cache.applyConversationSnapshot(cache.beginConversationSnapshot(), listOf(conversation(3, 1))))
                assertEquals(PendingConversationRead(CHAT, 10), cache.getPendingConversationRead(CHAT))
                cache.upsertConversation(conversation(4, 1))
                assertEquals(10L, cache.getConversations().single().readSeq)
            } finally { cache.close() }
        }
    }

    @Test
    fun `checkpoint retains unknown legacy read until message head disproves it durably`() = runBlocking {
        withDatabase { file ->
            seedLegacyFutureRead(file)
            val cache = open(file)
            try {
                cache.bindSyncDataset(DATASET)
                cache.applyServerProjectionCheckpoint(DATASET, 0, checkpoint(conversation(147, 40)))
                assertEquals(PendingConversationRead(CHAT, 330), cache.getPendingConversationRead(CHAT))
                val rpc = FakeRpcInvoker().apply {
                    enqueueOk(ProtoCodec.encode(ConversationPage(listOf(conversation(147, 40)), null)))
                    enqueueOk(ProtoCodec.encodeList(listOf(message(CHAT, 147))))
                }
                ConversationRepository(rpc, cache).listConversations().getOrThrow()
                assertEquals(40L, cache.getConversations().single().readSeq)
                assertEquals(107, cache.getConversations().single().unreadCount)
                assertNull(cache.getPendingConversationRead(CHAT))
            } finally { cache.close() }
            open(file).let { reopened ->
                try {
                    assertEquals(40L, reopened.getConversations().single().readSeq)
                    assertNull(reopened.getPendingConversationRead(CHAT))
                } finally { reopened.close() }
            }
        }
    }

    @Test
    fun `repair failure rolls back both read outbox and conversation projection`() = runBlocking {
        withDatabase { file ->
            seedLegacyFutureRead(file)
            val driver = driver(file)
            val cache = LocalCacheImpl(driver)
            try {
                val generation = cache.beginConversationSnapshot()
                driver.execute(null, "CREATE TRIGGER fail_repair BEFORE INSERT ON conversation BEGIN SELECT RAISE(ABORT, 'repair failure'); END", 0)
                assertFailsWith<Exception> { cache.applyConversationSnapshot(generation, listOf(conversation(147, 40)), mapOf(CHAT to 147L)) }
                assertEquals(330L, cache.getConversations().single().readSeq)
                assertEquals(PendingConversationRead(CHAT, 330), cache.getPendingConversationRead(CHAT))
                assertEquals(330L, AppDatabase(driver).appDatabaseQueries.selectAllConversationReadOutbox().executeAsOne().read_seq)
                driver.execute(null, "DROP TRIGGER fail_repair", 0)
                assertTrue(cache.applyConversationSnapshot(generation, listOf(conversation(147, 40)), mapOf(CHAT to 147L)))
                assertEquals(40L, cache.getConversations().single().readSeq)
                assertNull(cache.getPendingConversationRead(CHAT))
            } finally { cache.close() }
        }
    }

    @Test
    fun `legacy read without surviving projection proof is validated by Rocks head not lagging PG`() = runBlocking {
        withDatabase { file ->
            seedLegacyFutureRead(file, last = 3, read = 10)
            var cache = open(file)
            try {
                val rpc = FakeRpcInvoker().apply {
                    enqueueOk(ProtoCodec.encode(ConversationPage(listOf(conversation(3, 1)), null)))
                    enqueueOk(ProtoCodec.encodeList(listOf(message(CHAT, 10))))
                }
                ConversationRepository(rpc, cache).listConversations().getOrThrow()
                assertEquals(PendingConversationRead(CHAT, 10), cache.getPendingConversationRead(CHAT))
                assertEquals(10L, cache.getConversations().single().readSeq)
                cache.bindSyncDataset(DATASET)
                cache.applyServerProjectionCheckpoint(DATASET, 0, checkpoint(conversation(3, 1)))
                assertTrue(cache.getMessages(CHAT).isEmpty())
            } finally { cache.close() }
            cache = open(file)
            try {
                val rpc = FakeRpcInvoker().apply {
                    enqueueOk(ProtoCodec.encode(ConversationPage(listOf(conversation(3, 1)), null)))
                }
                ConversationRepository(rpc, cache).listConversations().getOrThrow()
                assertEquals(1, rpc.calls.size, "validated intent survives restart without another history lookup")
                assertEquals(PendingConversationRead(CHAT, 10), cache.getPendingConversationRead(CHAT))
                assertEquals(10L, cache.getConversations().single().readSeq)
            } finally { cache.close() }
        }
    }

    @Test
    fun `new validated read does not legitimize higher legacy corruption or disappear during repair`() = runBlocking {
        withDatabase { file ->
            seedLegacyFutureRead(file)
            val cache = open(file)
            try {
                val rpc = FakeRpcInvoker().apply {
                    enqueueOk(ProtoCodec.encode(ConversationPage(listOf(conversation(147, 40)), null)))
                    enqueueOk(ProtoCodec.encodeList(listOf(message(CHAT, 147))))
                    enqueueOk()
                }
                val messages = MessageRepository(rpc, cache)
                messages.markReadLocal(CHAT, 147)
                assertEquals(PendingConversationRead(CHAT, 330), cache.getPendingConversationRead(CHAT))
                var recoveryWakes = 0
                ConversationRepository(rpc, cache, onPendingMirrorCommitted = { recoveryWakes++ }).listConversations().getOrThrow()
                assertEquals(1, recoveryWakes, "repair must retry the lower intent even if the bad cursor already failed")
                assertEquals(147L, cache.getConversations().single().readSeq)
                assertEquals(PendingConversationRead(CHAT, 147), cache.getPendingConversationRead(CHAT))
                messages.retryPendingReads().getOrThrow()
                assertContentEquals(MessageRpcContract.encodeMarkRead(CHAT, 147), rpc.calls.last().third)
                assertNull(cache.getPendingConversationRead(CHAT))
            } finally { cache.close() }
        }
    }

    @Test
    fun `failed or wrong-chat legacy validation cannot erase an unknown read`() = runBlocking {
        withDatabase { file ->
            seedLegacyFutureRead(file)
            val cache = open(file)
            try {
                for (wrongChat in listOf(false, true)) {
                    val rpc = FakeRpcInvoker().apply {
                        enqueueOk(ProtoCodec.encode(ConversationPage(listOf(conversation(147, 40)), null)))
                        if (wrongChat) enqueueOk(ProtoCodec.encodeList(listOf(message("another-chat", 147))))
                        else enqueueError(504)
                    }
                    assertTrue(ConversationRepository(rpc, cache).listConversations() is Outcome.Failure)
                    assertEquals(330L, cache.getConversations().single().readSeq)
                    assertEquals(PendingConversationRead(CHAT, 330), cache.getPendingConversationRead(CHAT))
                }
            } finally { cache.close() }
        }
    }

    @Test
    fun `read arriving during legacy head lookup invalidates its snapshot and keeps new intent`() = runBlocking {
        withDatabase { file ->
            seedLegacyFutureRead(file)
            val cache = open(file)
            try {
                val responses = FakeRpcInvoker().apply {
                    enqueueOk(ProtoCodec.encode(ConversationPage(listOf(conversation(147, 40)), null)))
                    enqueueOk(ProtoCodec.encodeList(listOf(message(CHAT, 147))))
                    enqueueOk(ProtoCodec.encode(ConversationPage(listOf(conversation(148, 40)), null)))
                    enqueueOk(ProtoCodec.encodeList(listOf(message(CHAT, 148))))
                }
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val rpc = object : RpcInvoker {
                    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                        val response = responses.invoke(service, methodId, payload)
                        if (responses.calls.size == 2) { started.complete(Unit); release.await() }
                        return response
                    }
                }
                val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                    ConversationRepository(rpc, cache).listConversations()
                }
                started.await()
                cache.insertMessage(message(CHAT, 148))
                cache.enqueueConversationRead(CHAT, 148)
                release.complete(Unit)
                refresh.await().getOrThrow()
                assertEquals(4, responses.calls.size)
                assertEquals(148L, cache.getConversations().single().readSeq)
                assertEquals(PendingConversationRead(CHAT, 148), cache.getPendingConversationRead(CHAT))
            } finally { cache.close() }
        }
    }

    @Test
    fun `read validation migration is replayable and retains unknown legacy and validated intent`() = runBlocking {
        withDatabase { file ->
            seedLegacyFutureRead(file)
            driver(file).let { old ->
                try {
                    old.execute(null, "DROP TABLE conversation_read_validation", 0)
                    old.execute(null, "PRAGMA user_version = 4", 0)
                    migrateJvmLocalCache(old)
                    assertEquals(0L, AppDatabase(old).appDatabaseQueries.selectAllConversationReadOutbox().executeAsOne().validated_read_seq)
                } finally { old.close() }
            }
            val cache = open(file)
            try { cache.enqueueConversationRead(CHAT, 147) } finally { cache.close() }
            driver(file).let { migrated ->
                try {
                    AppDatabase.Schema.migrate(migrated, 4, AppDatabase.Schema.version)
                    val persisted = AppDatabase(migrated).appDatabaseQueries.selectAllConversationReadOutbox().executeAsOne()
                    assertEquals(330L, persisted.read_seq)
                    assertEquals(147L, persisted.validated_read_seq)
                } finally { migrated.close() }
            }
        }
    }

    private suspend fun withDatabase(block: suspend (File) -> Unit) {
        val root = createTempDirectory("conversation-read-recovery-").toFile()
        val file = root.resolve("cache.db")
        try {
            driver(file).let { initial -> try { AppDatabase.Schema.create(initial) } finally { initial.close() } }
            block(file)
        } finally { root.deleteRecursively() }
    }

    private fun driver(file: File) = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    private fun open(file: File) = LocalCacheImpl(driver(file))
    private fun seedLegacyFutureRead(file: File, last: Long = 147L, read: Long = 330L) {
        driver(file).let { seed ->
            try {
                seed.execute(null, "INSERT INTO conversation(chat_id, chat_type, last_seq, read_seq, unread_count) VALUES ('$CHAT', 2, $last, $read, 0)", 0)
                seed.execute(null, "INSERT INTO conversation_read_outbox(chat_id, read_seq) VALUES ('$CHAT', $read)", 0)
            } finally { seed.close() }
        }
    }
    private fun conversation(last: Long, read: Long) = Conversation(CHAT, 2, lastSeq = last, readSeq = read,
        unreadCount = (last - read).coerceAtLeast(0).toInt())
    private fun message(chatId: String, seq: Long) = Message(chatId, "confirmed-$seq", seq, "peer", 1, 1L)
    private fun checkpoint(conversation: Conversation) = ServerProjectionCheckpoint(DATASET, 1,
        User("owner", "owner", "Owner"), emptyList(), listOf(Chat(CHAT, 2)), listOf(conversation))
    private companion object {
        const val CHAT = "test-chat"
        const val DATASET = "00000000-0000-4000-8000-000000000001"
    }
}
