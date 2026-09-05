package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.domain.conversation.ConversationService
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ReadSyncPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 针对 Conversation 状态 + 持久事件原子性的真实 PostgreSQL 门禁测试。 */
class ConversationUnitOfWorkIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `preferences and deletion roll back together with their durable events`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("conversation-uow-owner"))
        val peer = ctx.registerUser(uniqueUsername("conversation-uow-peer"))
        val chat = ctx.chatService.createPersonalChat(owner, peer)
        val before = assertNotNull(ctx.conversationRepo.getConversation(owner, chat.chatId))
        val updatedEventsBefore = eventCount(owner, NotifyType.CONVERSATION_UPDATED)
        val deletedEventsBefore = eventCount(owner, NotifyType.CONVERSATION_DELETED)
        val failing = conversationService(failingUnitOfWork())

        assertRollback { failing.setDraft(owner, chat.chatId, "must roll back") }
        assertRollback { failing.setPin(owner, chat.chatId, true) }
        assertRollback { failing.setMute(owner, chat.chatId, true) }
        assertRollback { failing.deleteConversation(owner, chat.chatId) }

        assertEquals(before, ctx.conversationRepo.getConversation(owner, chat.chatId))
        assertEquals(updatedEventsBefore, eventCount(owner, NotifyType.CONVERSATION_UPDATED))
        assertEquals(deletedEventsBefore, eventCount(owner, NotifyType.CONVERSATION_DELETED))
    }

    @Test
    fun `mark read rolls back actor peer watermarks and every event as one unit`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("conversation-uow-read-sender"))
        val reader = ctx.registerUser(uniqueUsername("conversation-uow-read-reader"))
        val chat = ctx.chatService.createPersonalChat(sender, reader)
        val serverSeq = sendMessage(sender, chat.chatId, "rollback read receipt")
        val readerBefore = assertNotNull(ctx.conversationRepo.getConversation(reader, chat.chatId))
        val senderBefore = assertNotNull(ctx.conversationRepo.getConversation(sender, chat.chatId))
        val readerEventsBefore = eventCount(reader, NotifyType.CONVERSATION_UPDATED)
        val senderEventsBefore = eventCount(sender, NotifyType.READ_SYNC)

        assertRollback {
            conversationService(failingUnitOfWork()).markRead(reader, chat.chatId, serverSeq)
        }

        assertEquals(readerBefore, ctx.conversationRepo.getConversation(reader, chat.chatId))
        assertEquals(senderBefore, ctx.conversationRepo.getConversation(sender, chat.chatId))
        assertEquals(readerEventsBefore, eventCount(reader, NotifyType.CONVERSATION_UPDATED))
        assertEquals(senderEventsBefore, eventCount(sender, NotifyType.READ_SYNC))
    }

    @Test
    fun `committed event payloads are the exact persisted snapshots`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("conv-snapshot-sender"))
        val reader = ctx.registerUser(uniqueUsername("conv-snapshot-reader"))
        val chat = ctx.chatService.createPersonalChat(sender, reader)
        val serverSeq = sendMessage(sender, chat.chatId, "snapshot")

        ctx.conversationService.setDraft(reader, chat.chatId, "committed draft")
        val preferenceSnapshot = assertNotNull(ctx.conversationRepo.getConversation(reader, chat.chatId))
        assertEquals(
            preferenceSnapshot,
            ProtoCodec.decode(
                Conversation,
                latestEventPayload(reader, NotifyType.CONVERSATION_UPDATED),
            ),
        )

        ctx.conversationService.markRead(reader, chat.chatId, serverSeq)
        val readSnapshot = assertNotNull(ctx.conversationRepo.getConversation(reader, chat.chatId))
        val peerSnapshot = assertNotNull(ctx.conversationRepo.getConversation(sender, chat.chatId))
        assertEquals(
            readSnapshot,
            ProtoCodec.decode(
                Conversation,
                latestEventPayload(reader, NotifyType.CONVERSATION_UPDATED),
            ),
        )
        assertEquals(serverSeq, peerSnapshot.peerReadSeq)
        assertEquals(
            ReadSyncPayload(reader, chat.chatId, serverSeq),
            ProtoCodec.decode(
                ReadSyncPayload,
                latestEventPayload(sender, NotifyType.READ_SYNC),
            ),
        )
    }

    @Test
    fun `lost response preference retries emit authoritative snapshots without version churn`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("conversation-uow-noop-owner"))
        val peer = ctx.registerUser(uniqueUsername("conversation-uow-noop-peer"))
        val chat = ctx.chatService.createPersonalChat(owner, peer)

        ctx.conversationService.setDraft(owner, chat.chatId, "stable")
        ctx.conversationService.setPin(owner, chat.chatId, true)
        ctx.conversationService.setMute(owner, chat.chatId, true)
        val versionAfterChanges = conversationVersion(owner, chat.chatId)
        val eventsAfterChanges = eventCount(owner, NotifyType.CONVERSATION_UPDATED)

        ctx.conversationService.setDraft(owner, chat.chatId, "stable")
        ctx.conversationService.setPin(owner, chat.chatId, true)
        ctx.conversationService.setMute(owner, chat.chatId, true)

        assertEquals(versionAfterChanges, conversationVersion(owner, chat.chatId))
        assertEquals(eventsAfterChanges + 3L, eventCount(owner, NotifyType.CONVERSATION_UPDATED))
        assertEquals(
            ctx.conversationRepo.getConversation(owner, chat.chatId),
            ProtoCodec.decode(
                Conversation,
                latestEventPayload(owner, NotifyType.CONVERSATION_UPDATED),
            ),
        )

        ctx.conversationService.deleteConversation(owner, chat.chatId)
        val deletedEvents = eventCount(owner, NotifyType.CONVERSATION_DELETED)
        ctx.conversationService.deleteConversation(owner, chat.chatId)
        assertEquals(deletedEvents, eventCount(owner, NotifyType.CONVERSATION_DELETED))
    }

    @Test
    fun `lower read cursor and already advanced peers are event free noops`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("conv-noop-sender"))
        val reader = ctx.registerUser(uniqueUsername("conv-noop-reader"))
        val chat = ctx.chatService.createPersonalChat(sender, reader)
        val lowSeq = sendMessage(sender, chat.chatId, "first")
        val highSeq = sendMessage(sender, chat.chatId, "second")
        ctx.conversationService.markRead(reader, chat.chatId, highSeq)
        val actorVersion = conversationVersion(reader, chat.chatId)
        val peerVersion = conversationVersion(sender, chat.chatId)
        val actorEvents = eventCount(reader, NotifyType.CONVERSATION_UPDATED)
        val peerEvents = eventCount(sender, NotifyType.READ_SYNC)

        ctx.conversationService.markRead(reader, chat.chatId, lowSeq)

        assertEquals(actorVersion, conversationVersion(reader, chat.chatId))
        assertEquals(peerVersion, conversationVersion(sender, chat.chatId))
        assertEquals(actorEvents, eventCount(reader, NotifyType.CONVERSATION_UPDATED))
        assertEquals(peerEvents, eventCount(sender, NotifyType.READ_SYNC))
    }

    @Test
    fun `missing peer projection receives no read sync because no peer watermark advanced`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("conv-missing-sender"))
        val reader = ctx.registerUser(uniqueUsername("conv-missing-reader"))
        val chat = ctx.chatService.createPersonalChat(sender, reader)
        val serverSeq = sendMessage(sender, chat.chatId, "missing peer projection")
        ctx.conversationService.deleteConversation(sender, chat.chatId)
        val readEventsBefore = eventCount(sender, NotifyType.READ_SYNC)

        ctx.conversationService.markRead(reader, chat.chatId, serverSeq)

        assertEquals(serverSeq, ctx.conversationRepo.getConversation(reader, chat.chatId)?.readSeq)
        assertEquals(null, ctx.conversationRepo.getConversation(sender, chat.chatId))
        assertEquals(readEventsBefore, eventCount(sender, NotifyType.READ_SYNC))
    }

    @Test
    fun `lagging peer advances without duplicating unchanged actor event`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("conv-lagging-sender"))
        val reader = ctx.registerUser(uniqueUsername("conv-lagging-reader"))
        val chat = ctx.chatService.createPersonalChat(sender, reader)
        val serverSeq = sendMessage(sender, chat.chatId, "lagging peer")
        ctx.conversationService.markRead(reader, chat.chatId, serverSeq)

        // 在保留活动成员的前提下模拟一个可见的滞后对端投影。隐藏行有意不接收
        // READ_SYNC 事件，直到一条真正的新消息恢复其可见性。
        transaction(ctx.database) {
            Conversations.update({
                (Conversations.uid eq sender) and (Conversations.chatId eq chat.chatId)
            }) {
                it[Conversations.peerReadSeq] = 0L
                it[Conversations.isHidden] = false
            }
        }
        val actorEventsBefore = eventCount(reader, NotifyType.CONVERSATION_UPDATED)
        val peerEventsBefore = eventCount(sender, NotifyType.READ_SYNC)

        ctx.conversationService.markRead(reader, chat.chatId, serverSeq)

        assertEquals(actorEventsBefore, eventCount(reader, NotifyType.CONVERSATION_UPDATED))
        assertEquals(peerEventsBefore + 1L, eventCount(sender, NotifyType.READ_SYNC))
        assertEquals(serverSeq, ctx.conversationRepo.getConversation(sender, chat.chatId)?.peerReadSeq)
    }

    @Test
    fun `concurrent high and low mark read commits never regress state or receipts`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("conversation-uow-race-sender"))
        val reader = ctx.registerUser(uniqueUsername("conversation-uow-race-reader"))
        val chat = ctx.chatService.createPersonalChat(sender, reader)
        val lowSeq = sendMessage(sender, chat.chatId, "low")
        val highSeq = sendMessage(sender, chat.chatId, "high")
        val peerEventBaseline = latestEventSeq(sender)
        val start = CompletableDeferred<Unit>()
        val services = listOf(
            conversationService(ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {})) to highSeq,
            conversationService(ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {})) to lowSeq,
        )

        val writes = services.map { (service, requestedSeq) ->
            async(Dispatchers.IO) {
                start.await()
                service.markRead(reader, chat.chatId, requestedSeq)
            }
        }
        start.complete(Unit)
        withContext(Dispatchers.IO) {
            withTimeout(10_000) { writes.awaitAll() }
        }

        assertEquals(highSeq, ctx.conversationRepo.getConversation(reader, chat.chatId)?.readSeq)
        assertEquals(highSeq, ctx.conversationRepo.getConversation(sender, chat.chatId)?.peerReadSeq)
        val receipts = eventPayloadsAfter(sender, NotifyType.READ_SYNC, peerEventBaseline)
            .map { ProtoCodec.decode(ReadSyncPayload, it).peerReadSeq }
        assertTrue(receipts.size in 1..2)
        assertTrue(receipts.zipWithNext().all { (previous, next) -> previous <= next })
        assertEquals(highSeq, receipts.last())
    }

    @Test
    fun `conversation repository rejects a foreign transaction handle`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("conversation-uow-context-owner"))
        val peer = ctx.registerUser(uniqueUsername("conversation-uow-context-peer"))
        val chat = ctx.chatService.createPersonalChat(owner, peer)

        assertFailsWith<IllegalStateException> {
            ctx.conversationRepo.setPin(object : PgWriteTransactionContext {}, owner, chat.chatId, true)
        }
        assertEquals(false, ctx.conversationRepo.getConversation(owner, chat.chatId)?.isPinned)
    }

    private fun conversationService(unitOfWork: PgUnitOfWork): ConversationService = ConversationService(
        conversationRepo = ctx.conversationRepo,
        lifecycleGate = ChatLifecycleGate(),
        unitOfWork = unitOfWork,
    )

    private fun failingUnitOfWork(): PgUnitOfWork = ExposedPgUnitOfWork(
        database = ctx.database,
        onEventsCommitted = {},
        hooks = PgUnitOfWorkHooks { stage ->
            if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                throw InjectedConversationRollback
            }
        },
    )

    private suspend fun assertRollback(block: suspend () -> Unit) {
        assertIs<InjectedConversationRollbackException>(runCatching { block() }.exceptionOrNull())
    }

    private fun eventCount(uid: String, type: NotifyType): Long = transaction(ctx.database) {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq type.code)
        }.count()
    }

    private fun latestEventSeq(uid: String): Long = transaction(ctx.database) {
        SyncEvents.selectAll().where { SyncEvents.uid eq uid }
            .orderBy(SyncEvents.streamSeq, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(SyncEvents.streamSeq)
            ?: 0L
    }

    private fun conversationVersion(uid: String, chatId: String): Long? = transaction(ctx.database) {
        Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }.singleOrNull()?.get(Conversations.version)
    }

    private fun latestEventPayload(uid: String, type: NotifyType): ByteArray = transaction(ctx.database) {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq type.code)
        }.orderBy(SyncEvents.streamSeq, SortOrder.DESC)
            .limit(1)
            .single()[SyncEvents.payload]
    }

    private fun eventPayloadsAfter(uid: String, type: NotifyType, afterSeq: Long): List<ByteArray> = transaction(ctx.database) {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and
                (SyncEvents.eventType eq type.code) and
                (SyncEvents.streamSeq greater afterSeq)
        }.orderBy(SyncEvents.streamSeq, SortOrder.ASC)
            .map { it[SyncEvents.payload] }
    }

    private suspend fun sendMessage(senderUid: String, chatId: String, text: String): Long =
        ctx.messageService.sendMessage(
            senderUid,
            Message(
                chatId = chatId,
                clientMsgId = java.util.UUID.randomUUID().toString(),
                senderUid = senderUid,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                body = com.virjar.tk.protocol.body.buildRichTextBody(text),
            ),
        )

    private object InjectedConversationRollback : InjectedConversationRollbackException()
    private open class InjectedConversationRollbackException : RuntimeException("injected conversation rollback")
}
