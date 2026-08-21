package com.virjar.tk.integration

import com.virjar.tk.recoverStartupProjections
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.chat.UnmanagedChatPolicy
import com.virjar.tk.domain.message.MessageOperationType
import com.virjar.tk.domain.message.MessageProjectionApplyResult
import com.virjar.tk.domain.message.MessageProjectionHooks
import com.virjar.tk.domain.message.MessageProjectionOperation
import com.virjar.tk.domain.message.MessageProjectionRepository
import com.virjar.tk.domain.message.MessageProjectionStage
import com.virjar.tk.domain.message.MessageProjectionTarget
import com.virjar.tk.domain.message.MessageSearch
import com.virjar.tk.domain.message.MessageSearchPage
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.transaction.PgWriteScope
import com.virjar.tk.infra.db.AutomationBotGrants
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.ExternalProjectionReceipts
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.infra.db.PgUnitOfWorkStage
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessageProjectionIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()

        private const val PENDING_OPERATION_COUNT = 1_001
    }

    private val ctx get() = ext.env

    @Test
    fun `postgres commit before rocks ack replays without new event ids including bot inbox`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("projection-owner"))
        val member = ctx.registerUser(uniqueUsername("projection-member"))
        val group = ctx.chatService.createGroup("Projection bot inbox", null, owner, listOf(member))
        val credentials = ctx.botService.createForGroup(owner, group.chatId, "Projection bot")
        val botUid = ctx.botService.list().single { it.botId == credentials.bot.botId }.userUid
        val recipients = listOf(owner, member, botUid).sorted()
        val before = recipients.associateWith(::allDurableEvents)
        val crashed = AtomicBoolean(false)
        val crashService = ctx.freshMessageService(
            projectionHooks = MessageProjectionHooks { stage, _ ->
                if (
                    stage == MessageProjectionStage.AFTER_POSTGRES_BEFORE_ROCKS_ACK &&
                    crashed.compareAndSet(false, true)
                ) {
                    throw InjectedProjectionCrash()
                }
            },
        )
        val message = textMessage(group.chatId, owner, "commit-before-ack", "durable projection")

        assertFailsWith<InjectedProjectionCrash> { crashService.sendMessage(owner, message) }
        val operation = pendingOperation(message.clientMsgId)
        assertEquals(MessageOperationType.CREATE, operation.operation)
        assertEquals(1L, operation.revision)
        assertEquals(1L, receiptCount(operation.projectionKey, operation.revision))
        assertNotNull(ctx.messageProjectionReadiness.currentFailure())
        assertEquals("DOWN", ctx.healthChecker.check().components["message-projection"]?.status)

        val committedEvents = recipients.associateWith(::allDurableEvents)
        val committedConversations = recipients.associateWith { uid -> conversationProjection(uid, group.chatId) }
        recipients.forEach { uid ->
            val messageDelta = committedEvents.getValue(uid).drop(before.getValue(uid).size)
                .count { it.notifyType == NotifyType.MESSAGE_RECV.code }
            assertEquals(1, messageDelta)
        }

        assertEquals(1, ctx.messageService.recoverPendingProjections())
        assertTrue(ctx.messageStore.getPendingProjectionOperations().isEmpty())
        assertEquals(1L, receiptCount(operation.projectionKey, operation.revision))
        assertEquals(committedEvents, recipients.associateWith(::allDurableEvents))
        assertEquals(
            committedConversations,
            recipients.associateWith { uid -> conversationProjection(uid, group.chatId) },
        )
        assertEquals(null, ctx.messageProjectionReadiness.currentFailure())
        assertEquals("UP", ctx.healthChecker.check().components["message-projection"]?.status)

        val botPayloads = ctx.syncEventReader.getEventsAfter(botUid, 0L, 10_000)
            .filter { it.notifyType == NotifyType.MESSAGE_RECV.code }
            .map { ProtoCodec.decode(Message, requireNotNull(it.payload)) }
            .filter { it.chatId == group.chatId && it.serverSeq == operation.message.serverSeq }
        assertEquals(1, botPayloads.size)
    }

    @Test
    fun `postgres rollback leaves receipt conversation and events absent then recovery applies once`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("projection-rollback-sender"))
        val peer = ctx.registerUser(uniqueUsername("projection-rollback-peer"))
        val chat = ctx.chatService.createPersonalChat(sender, peer)
        val beforeEvents = allDurableEvents(peer)
        val beforeConversation = conversationProjection(peer, chat.chatId)
        val rollbackUnitOfWork = projectionRollbackUnitOfWork()
        val rollbackService = ctx.freshMessageService(unitOfWork = rollbackUnitOfWork)
        val message = textMessage(chat.chatId, sender, "projection-rollback", "rollback searchable")

        assertFailsWith<InjectedProjectionRollback> { rollbackService.sendMessage(sender, message) }
        val operation = pendingOperation(message.clientMsgId)
        assertEquals(0L, receiptCount(operation.projectionKey, operation.revision))
        assertEquals(beforeEvents, allDurableEvents(peer))
        assertEquals(beforeConversation, conversationProjection(peer, chat.chatId))
        // Lucene committed first; it may safely lead PostgreSQL because the authoritative Rocks
        // message already exists and the operation remains pending.
        assertEquals(
            listOf(operation.message.serverSeq),
            ctx.searchIndex.search("rollback searchable", setOf(chat.chatId)).hits.map { it.seq },
        )
        assertNotNull(ctx.messageProjectionReadiness.currentFailure())

        assertEquals(1, ctx.messageService.recoverPendingProjections())
        assertEquals(1L, receiptCount(operation.projectionKey, operation.revision))
        assertEquals(
            1,
            allDurableEvents(peer).drop(beforeEvents.size)
                .count { it.notifyType == NotifyType.MESSAGE_RECV.code },
        )
        assertEquals(operation.message.serverSeq, conversationProjection(peer, chat.chatId).lastMsgSeq)
        assertTrue(ctx.messageStore.getPendingProjectionOperations().isEmpty())
        assertEquals(null, ctx.messageProjectionReadiness.currentFailure())
    }

    @Test
    fun `member removed after pre-commit failure never receives a stale message event during recovery`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("projection-removal-owner"))
        val member = ctx.registerUser(uniqueUsername("projection-removal-member"))
        val group = ctx.chatService.createGroup("Projection removal ordering", null, owner, listOf(member))
        val rollbackUnitOfWork = projectionRollbackUnitOfWork()
        val rollbackService = ctx.freshMessageService(unitOfWork = rollbackUnitOfWork)
        val message = textMessage(group.chatId, owner, "projection-before-removal", "accepted before removal")

        assertFailsWith<InjectedProjectionRollback> { rollbackService.sendMessage(owner, message) }
        val operation = pendingOperation(message.clientMsgId)
        assertEquals(listOf(owner, member).sorted(), operation.target.recipientUids)

        // Chat mutation is allowed to commit after the failed command. Recovery must use the
        // persisted maximum snapshot intersected with current membership, never append a stale
        // MESSAGE_RECV after this member-removal event sequence.
        ctx.chatService.removeMember(owner, group.chatId, member)
        val removedMemberEvents = allDurableEvents(member)
        val ownerMessagesBeforeRecovery = messageEventIds(owner).size

        assertEquals(1, ctx.messageService.recoverPendingProjections())
        assertEquals(removedMemberEvents, allDurableEvents(member))
        assertEquals(ownerMessagesBeforeRecovery + 1, messageEventIds(owner).size)
        assertEquals(1L, receiptCount(operation.projectionKey, operation.revision))
        assertTrue(ctx.messageStore.getPendingProjectionOperations().isEmpty())
    }

    @Test
    fun `dissolved chat after pre-commit failure terminates outbox without stale events`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("projection-dissolve-owner"))
        val member = ctx.registerUser(uniqueUsername("projection-dissolve-member"))
        val group = ctx.chatService.createGroup("Projection dissolve ordering", null, owner, listOf(member))
        val rollbackUnitOfWork = projectionRollbackUnitOfWork()
        val rollbackService = ctx.freshMessageService(unitOfWork = rollbackUnitOfWork)

        val message = textMessage(
            group.chatId,
            owner,
            "projection-before-dissolve",
            "accepted before dissolve",
        )
        assertFailsWith<InjectedProjectionRollback> {
            rollbackService.sendMessage(
                owner,
                message,
            )
        }
        val operation = pendingOperation(message.clientMsgId)
        ctx.chatService.dissolveGroup(owner, group.chatId)
        val eventsAfterDissolve = listOf(owner, member).associateWith(::allDurableEvents)

        assertEquals(1, ctx.messageService.recoverPendingProjections())
        assertEquals(eventsAfterDissolve, listOf(owner, member).associateWith(::allDurableEvents))
        assertEquals(1L, receiptCount(operation.projectionKey, operation.revision))
        assertTrue(ctx.messageStore.getPendingProjectionOperations().isEmpty())
        assertEquals(null, ctx.messageProjectionReadiness.currentFailure())
    }

    @Test
    fun `pending managed revision fences message projection before receipt and events`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("projection-managed-owner"))
        val member = ctx.registerUser(uniqueUsername("projection-managed-member"))
        val rootId = organizationRootId()
        val unit = ctx.organizationService.createUnit(
            rootId,
            "Managed projection fence ${uniqueUsername("unit")}",
            owner,
            enableGroup = true,
        )
        ctx.organizationService.assignMember(unit.unitId, member, null, primary = false)
        val message = textMessage(unit.unitId, owner, uniqueUsername("managed-pending"), "pending removal")

        assertFailsWith<InjectedProjectionRollback> {
            ctx.freshMessageService(unitOfWork = projectionRollbackUnitOfWork()).sendMessage(owner, message)
        }
        val operation = pendingOperation(message.clientMsgId)
        val memberMessageEvents = messageEventIds(member)
        val removal = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.removeMember(transaction, unit.unitId, member)
        }.projections.single { it.unitId == unit.unitId }
        assertFalse(ctx.organizationRepo.isProjectionReady(unit.unitId))

        val pendingFailure = assertFailsWith<IllegalArgumentException> {
            ctx.messageService.recoverPendingProjections()
        }
        assertEquals("受管群投影尚未收敛", pendingFailure.message)
        assertEquals(0L, receiptCount(operation.projectionKey, operation.revision))
        assertTrue(ctx.messageStore.isProjectionPending(operation))
        assertEquals(memberMessageEvents, messageEventIds(member))

        assertTrue(ctx.organizationProjector.project(removal))
        assertEquals(1, ctx.messageService.recoverPendingProjections())
        assertEquals(1L, receiptCount(operation.projectionKey, operation.revision))
        assertFalse(ctx.messageStore.isProjectionPending(operation))
        assertEquals(memberMessageEvents, messageEventIds(member))
    }

    @Test
    fun `startup recovery converges managed authority before replaying message projections`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("startup-managed-owner"))
        val member = ctx.registerUser(uniqueUsername("startup-managed-member"))
        val rootId = organizationRootId()
        val unit = ctx.organizationService.createUnit(
            rootId,
            "Startup projection order ${uniqueUsername("unit")}",
            owner,
            enableGroup = true,
        )
        ctx.organizationService.assignMember(unit.unitId, member, null, primary = false)
        val message = textMessage(unit.unitId, owner, uniqueUsername("startup-pending"), "startup removal")

        assertFailsWith<InjectedProjectionRollback> {
            ctx.freshMessageService(unitOfWork = projectionRollbackUnitOfWork()).sendMessage(owner, message)
        }
        val operation = pendingOperation(message.clientMsgId)
        val memberMessageEvents = messageEventIds(member)
        ctx.pgUnitOfWork.write {
            ctx.organizationRepo.removeMember(transaction, unit.unitId, member)
        }
        assertFalse(ctx.organizationRepo.isProjectionReady(unit.unitId))
        assertEquals(1L, ctx.organizationProjectionStore.countPending())

        assertEquals(1, recoverStartupProjections(ctx.organizationService, ctx.botService, ctx.messageService))

        assertTrue(ctx.organizationRepo.isProjectionReady(unit.unitId))
        assertEquals(0L, ctx.organizationProjectionStore.countPending())
        assertFalse(ctx.messageStore.isProjectionPending(operation))
        assertEquals(1L, receiptCount(operation.projectionKey, operation.revision))
        assertEquals(memberMessageEvents, messageEventIds(member))
    }

    @Test
    fun `startup bot recovery removes no-grant orphan before pending message replay`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("startup-bot-orphan-owner"))
        val group = ctx.chatService.createGroup("Startup bot orphan ordering", null, owner, emptyList())
        val credentials = ctx.botService.createForGroup(owner, group.chatId, "Startup orphan bot")
        val bot = ctx.botService.list().single { it.botId == credentials.bot.botId }
        val message = textMessage(
            group.chatId,
            owner,
            uniqueUsername("startup-bot-orphan-pending"),
            "must not reach revoked bot",
        )

        assertFailsWith<InjectedProjectionRollback> {
            ctx.freshMessageService(unitOfWork = projectionRollbackUnitOfWork()).sendMessage(owner, message)
        }
        val operation = pendingOperation(message.clientMsgId)
        val botMessageEvents = messageEventIds(bot.userUid)
        transaction {
            AutomationBotGrants.deleteWhere {
                (AutomationBotGrants.botId eq bot.botId) and
                    (AutomationBotGrants.chatId eq group.chatId)
            }
        }
        assertFalse(hasGrant(bot.botId, group.chatId))
        assertTrue(isActiveMember(group.chatId, bot.userUid))
        assertTrue(hasConversation(group.chatId, bot.userUid))

        assertEquals(1, recoverStartupProjections(ctx.organizationService, ctx.botService, ctx.messageService))

        assertFalse(isActiveMember(group.chatId, bot.userUid))
        assertFalse(hasConversation(group.chatId, bot.userUid))
        assertEquals(botMessageEvents, messageEventIds(bot.userUid))
        assertFalse(ctx.messageStore.isProjectionPending(operation))
        assertEquals(1L, receiptCount(operation.projectionKey, operation.revision))
    }

    @Test
    fun `startup bot recovery restores active grant membership before pending message replay`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("startup-bot-restore-owner"))
        val group = ctx.chatService.createGroup("Startup bot restore ordering", null, owner, emptyList())
        val credentials = ctx.botService.createForGroup(owner, group.chatId, "Startup restored bot")
        val bot = ctx.botService.list().single { it.botId == credentials.bot.botId }
        val message = textMessage(
            group.chatId,
            owner,
            uniqueUsername("startup-bot-restore-pending"),
            "must reach restored bot",
        )

        assertFailsWith<InjectedProjectionRollback> {
            ctx.freshMessageService(unitOfWork = projectionRollbackUnitOfWork()).sendMessage(owner, message)
        }
        val operation = pendingOperation(message.clientMsgId)
        val botMessageEvents = messageEventIds(bot.userUid)
        transaction {
            GroupMembers.update({
                (GroupMembers.chatId eq group.chatId) and (GroupMembers.uid eq bot.userUid)
            }) {
                it[status] = 0
            }
            Conversations.deleteWhere {
                (Conversations.chatId eq group.chatId) and (Conversations.uid eq bot.userUid)
            }
        }
        assertTrue(hasGrant(bot.botId, group.chatId))
        assertFalse(isActiveMember(group.chatId, bot.userUid))
        assertFalse(hasConversation(group.chatId, bot.userUid))

        assertEquals(1, recoverStartupProjections(ctx.organizationService, ctx.botService, ctx.messageService))

        assertTrue(isActiveMember(group.chatId, bot.userUid))
        assertTrue(hasConversation(group.chatId, bot.userUid))
        assertEquals(botMessageEvents.size + 1, messageEventIds(bot.userUid).size)
        assertEquals(operation.message.serverSeq, conversationProjection(bot.userUid, group.chatId).lastMsgSeq)
        assertFalse(ctx.messageStore.isProjectionPending(operation))
        assertEquals(1L, receiptCount(operation.projectionKey, operation.revision))
    }

    @Test
    fun `edit and revoke commit crashes replay without duplicate events or revisions`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("projection-revision-sender"))
        val peer = ctx.registerUser(uniqueUsername("projection-revision-peer"))
        val chat = ctx.chatService.createPersonalChat(sender, peer)
        val original = textMessage(chat.chatId, sender, "projection-revisions", "revision original")
        val before = messageEventIds(peer).size
        val seq = ctx.messageService.sendMessage(sender, original)
        val edit = original.copy(serverSeq = seq, body = buildRichTextBody("revision edited"))

        val editCrash = ctx.freshMessageService(
            projectionHooks = crashOnceAt(MessageProjectionStage.AFTER_POSTGRES_BEFORE_ROCKS_ACK),
        )
        assertFailsWith<InjectedProjectionCrash> {
            editCrash.editMessage(sender, chat.chatId, seq, edit)
        }
        val afterEditCommit = allDurableEvents(peer)
        val editConversation = conversationProjection(peer, chat.chatId)
        assertEquals(1, ctx.messageService.recoverPendingProjections())
        assertEquals(afterEditCommit, allDurableEvents(peer))
        assertEquals(editConversation, conversationProjection(peer, chat.chatId))
        ctx.messageService.editMessage(sender, chat.chatId, seq, edit)

        val revokeCrash = ctx.freshMessageService(
            projectionHooks = crashOnceAt(MessageProjectionStage.AFTER_POSTGRES_BEFORE_ROCKS_ACK),
        )
        assertFailsWith<InjectedProjectionCrash> {
            revokeCrash.revokeMessage(sender, chat.chatId, seq)
        }
        val afterRevokeCommit = allDurableEvents(peer)
        val revokeConversation = conversationProjection(peer, chat.chatId)
        assertEquals(1, ctx.messageService.recoverPendingProjections())
        assertEquals(afterRevokeCommit, allDurableEvents(peer))
        assertEquals(revokeConversation, conversationProjection(peer, chat.chatId))
        ctx.messageService.revokeMessage(sender, chat.chatId, seq)

        assertEquals(listOf(1L, 2L, 3L), receiptRevisions(chat.chatId, seq))
        assertEquals(before + 3, messageEventIds(peer).size)
        assertTrue(ctx.messageStore.getPendingProjectionOperations().isEmpty())
        assertTrue(ctx.searchIndex.search("revision original", setOf(chat.chatId)).hits.isEmpty())
        assertTrue(ctx.searchIndex.search("revision edited", setOf(chat.chatId)).hits.isEmpty())
    }

    @Test
    fun `global recovery drains beyond one thousand operation page`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("projection-drain-owner"))
        val group = ctx.chatService.createGroup("Projection drain", null, owner, emptyList())
        val target = MessageProjectionTarget(chatType = 2, recipientUids = listOf(owner))
        // The class intentionally shares one isolated environment to keep the PG suite fast. Count
        // any operation left by an earlier failed assertion so this pagination test reports its own
        // boundary instead of cascading with a misleading 1002-vs-1001 failure.
        val preExistingPending = ctx.messageStore.getPendingProjectionOperations(limit = 100_000).size
        repeat(PENDING_OPERATION_COUNT) { index ->
            val message = textMessage(
                group.chatId,
                owner,
                "pending-$index",
                "pending operation $index",
            ).copy(serverSeq = index + 1L, timestamp = index + 1L)
            ctx.messageStore.storeMessage(message, message, target)
        }
        val service = ctx.freshMessageService(
            unitOfWork = ImmediateNoOpUnitOfWork,
            projectionRepository = MessageProjectionRepository { _, _, _ ->
                MessageProjectionApplyResult(applied = false, recipients = emptyList())
            },
            search = NoOpMessageSearch,
            managedChats = UnmanagedChatPolicy,
        )

        assertEquals(preExistingPending + PENDING_OPERATION_COUNT, service.recoverPendingProjections(limit = 1_000))
        assertTrue(ctx.messageStore.getPendingProjectionOperations(limit = 1).isEmpty())
    }

    private fun textMessage(chatId: String, senderUid: String, clientMsgId: String, text: String) = Message(
        chatId = chatId,
        clientMsgId = clientMsgId,
        senderUid = senderUid,
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1L,
        body = buildRichTextBody(text),
    )

    private fun crashOnceAt(expected: MessageProjectionStage): MessageProjectionHooks {
        val crashed = AtomicBoolean(false)
        return MessageProjectionHooks { actual, _ ->
            if (actual == expected && crashed.compareAndSet(false, true)) throw InjectedProjectionCrash()
        }
    }

    private fun pendingOperation(clientMsgId: String): MessageProjectionOperation =
        ctx.messageStore.getPendingProjectionOperations(limit = 100_000)
            .single { it.message.clientMsgId == clientMsgId }

    private suspend fun organizationRootId(): String =
        ctx.organizationService.listUnits().singleOrNull { it.parentId == null }?.unitId
            ?: ctx.organizationService.createUnit(null, "Message projection test root", null).unitId

    /**
     * A new-message command now has two PostgreSQL units of work: admission/sequence allocation,
     * then the external projection. Fail the latter so the Rocks operation remains recoverable.
     */
    private fun projectionRollbackUnitOfWork(): ExposedPgUnitOfWork {
        val committedBoundaries = AtomicInteger()
        return ExposedPgUnitOfWork(
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT &&
                    committedBoundaries.incrementAndGet() == 2
                ) {
                    throw InjectedProjectionRollback()
                }
            },
        )
    }

    private fun messageEventIds(uid: String): List<Long> =
        ctx.syncEventReader.getEventsAfter(uid, 0L, 10_000)
            .filter { it.notifyType == NotifyType.MESSAGE_RECV.code }
            .map { it.eventId }

    private fun allDurableEvents(uid: String): List<EventSnapshot> =
        ctx.syncEventReader.getEventsAfter(uid, 0L, 10_000).map { event ->
            EventSnapshot(
                eventId = event.eventId,
                notifyType = event.notifyType,
                payload = event.payload?.toList(),
            )
        }

    private fun receiptCount(projectionKey: String, revision: Long): Long = transaction {
        ExternalProjectionReceipts.selectAll().where {
            (ExternalProjectionReceipts.projectionKey eq projectionKey) and
                (ExternalProjectionReceipts.revision eq revision)
        }.count()
    }

    private fun receiptRevisions(chatId: String, serverSeq: Long): List<Long> = transaction {
        ExternalProjectionReceipts.selectAll().where {
            (ExternalProjectionReceipts.chatId eq chatId) and
                (ExternalProjectionReceipts.serverSeq eq serverSeq)
        }.orderBy(ExternalProjectionReceipts.revision to SortOrder.ASC)
            .map { it[ExternalProjectionReceipts.revision] }
    }

    private fun hasGrant(botId: String, chatId: String): Boolean = transaction {
        AutomationBotGrants.selectAll().where {
            (AutomationBotGrants.botId eq botId) and (AutomationBotGrants.chatId eq chatId)
        }.count() == 1L
    }

    private fun isActiveMember(chatId: String, uid: String): Boolean = transaction {
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq uid) and
                (GroupMembers.status eq 1)
        }.count() == 1L
    }

    private fun hasConversation(chatId: String, uid: String): Boolean = transaction {
        Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid eq uid)
        }.count() == 1L
    }

    private fun conversationProjection(uid: String, chatId: String): ConversationProjection = transaction {
        Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }.single().let { row ->
            ConversationProjection(
                lastMsgSeq = row[Conversations.lastMsgSeq],
                readSeq = row[Conversations.readSeq],
                version = row[Conversations.version],
                lastMessage = row[Conversations.lastMessage],
                lastMessageType = row[Conversations.lastMessageType],
            )
        }
    }

    private data class EventSnapshot(
        val eventId: Long,
        val notifyType: Int,
        val payload: List<Byte>?,
    )

    private data class ConversationProjection(
        val lastMsgSeq: Long,
        val readSeq: Long,
        val version: Long,
        val lastMessage: String?,
        val lastMessageType: Int,
    )
}

private class InjectedProjectionCrash : RuntimeException("injected crash after PostgreSQL commit")
private class InjectedProjectionRollback : RuntimeException("injected rollback before PostgreSQL commit")

private object ImmediateNoOpUnitOfWork : PgUnitOfWork {
    private object TransactionContext : PgTransactionContext

    override suspend fun <T> write(block: suspend PgWriteScope.() -> T): T = block(
        object : PgWriteScope {
            override val transaction: PgTransactionContext = TransactionContext
            override fun appendEvent(uid: String, notifyType: NotifyType, payload: IProto, dedupeKey: String?) = Unit
            override fun afterCommit(action: () -> Unit) = action()
        },
    )
}

private object NoOpMessageSearch : MessageSearch {
    override fun applyProjection(
        operation: com.virjar.tk.domain.message.MessageProjectionOperation,
        text: String?,
    ): Boolean = true

    override fun search(
        query: String,
        chatIds: Set<String>,
        senderUid: String?,
        startTimestamp: Long?,
        endTimestamp: Long?,
        limit: Int,
        offset: Int,
    ): MessageSearchPage = MessageSearchPage(total = 0, hits = emptyList())
}
