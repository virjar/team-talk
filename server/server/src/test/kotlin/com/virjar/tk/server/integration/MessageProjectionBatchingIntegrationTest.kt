package com.virjar.tk.server.integration

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.domain.message.MessageOperationType
import com.virjar.tk.server.domain.message.MessageProjectionApplyResult
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageProjectionTarget
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.ConversationUsages
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.ExternalProjectionReceipts
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.SyncStreams
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.GroupPolicy
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SqlLogger
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.statements.StatementContext
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageProjectionBatchingIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `full group projection is batched and its post-flush failure rolls back atomically`() = runTest {
        val fixture = createFullGroupFixture()
        val operation = fixture.createOperation()
        val failingUnitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) throw InjectedBatchRollback
            },
        )

        assertFailsWith<InjectedBatchRollbackException> {
            failingUnitOfWork.write { projectAndAppend(operation) }
        }
        assertProjectionArtifacts(fixture, operation, expectedRowsPerTable = 0)

        var statementCount = 0
        val statementShapes = ProjectionStatementShapes()
        val result = ctx.pgUnitOfWork.write {
            transaction.requireExposedTransaction().addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) {
                    statementCount += 1
                    statementShapes.observe(context.sql(transaction))
                }
            })
            projectAndAppend(operation)
        }

        assertTrue(
            statementCount <= MAX_FULL_GROUP_PROJECTION_STATEMENTS,
            "full-group message projection used $statementCount SQL statements",
        )
        assertEquals(GroupPolicy.MAX_MEMBERS, result.recipients.size)
        assertTrue(result.recipients.all { recipient -> recipient.conversation?.chatName == fixture.groupName })
        assertEquals(2, statementShapes.conversationUpserts)
        assertEquals(2, statementShapes.usageInserts)
        assertEquals(2, statementShapes.streamInserts)
        assertEquals(1, statementShapes.eventInserts)
        assertProjectionArtifacts(fixture, operation, expectedRowsPerTable = GroupPolicy.MAX_MEMBERS)
    }

    @Test
    fun `batched revisions preserve hidden last-row and captured-membership semantics`() = runTest {
        val fixture = createGroupFixture(memberCount = 3)
        val sender = fixture.memberUids[0]
        val hiddenUid = fixture.memberUids[1]
        val removedUid = fixture.memberUids[2]
        val earlierCreate = fixture.createOperation(
            serverSeq = 1L,
            operationType = MessageOperationType.CREATE,
            revision = 1L,
            senderUid = sender,
            text = "earlier create",
        )
        ctx.pgUnitOfWork.write { projectAndAppend(earlierCreate, preview = "earlier create") }
        val currentCreate = fixture.createOperation(
            serverSeq = 2L,
            operationType = MessageOperationType.CREATE,
            revision = 1L,
            senderUid = sender,
            text = "current create",
        )
        ctx.pgUnitOfWork.write { projectAndAppend(currentCreate, preview = "current create") }
        transaction(ctx.database) {
            Conversations.update({
                (Conversations.uid eq hiddenUid) and (Conversations.chatId eq fixture.chatId)
            }) {
                it[Conversations.isHidden] = true
            }
        }

        // 此时 CREATE 投影已连续。对已提交的较早消息进行重放是回执 no-op，
        // 并且不能让用户隐藏的行重新可见。
        val eventsBeforeReplay = fixture.memberUids.associateWith(::eventSequences)
        val replay = ctx.pgUnitOfWork.write {
            projectAndAppend(earlierCreate, preview = "earlier create")
        }
        assertTrue(!replay.applied)
        assertEquals(eventsBeforeReplay, fixture.memberUids.associateWith(::eventSequences))
        assertConversationState(
            hiddenUid,
            fixture.chatId,
            lastSeq = 2L,
            hidden = true,
            lastMessage = "current create",
        )

        val editedMessage = currentCreate.message.copy(body = buildRichTextBody("edited current"))
        val edit = currentCreate.copy(
            operation = MessageOperationType.EDIT,
            revision = 2L,
            message = editedMessage,
        )
        val conversationEventsBeforeEdit = fixture.memberUids.associateWith {
            eventCount(it, NotifyType.CONVERSATION_UPDATED)
        }
        val hiddenMessageEventsBeforeEdit = eventCount(hiddenUid, NotifyType.MESSAGE_RECV)
        val editResult = ctx.pgUnitOfWork.write { projectAndAppend(edit, preview = "edited current") }
        assertEquals(null, editResult.recipient(hiddenUid).conversation)
        assertEquals("edited current", editResult.recipient(sender).conversation?.lastMessage)
        assertEquals(
            conversationEventsBeforeEdit.getValue(hiddenUid),
            eventCount(hiddenUid, NotifyType.CONVERSATION_UPDATED),
        )
        assertEquals(
            conversationEventsBeforeEdit.getValue(sender) + 1L,
            eventCount(sender, NotifyType.CONVERSATION_UPDATED),
        )
        assertEquals(hiddenMessageEventsBeforeEdit + 1L, eventCount(hiddenUid, NotifyType.MESSAGE_RECV))
        assertConversationState(
            hiddenUid,
            fixture.chatId,
            lastSeq = 2L,
            hidden = true,
            lastMessage = "edited current",
        )

        val revoke = edit.copy(
            operation = MessageOperationType.REVOKE,
            revision = 3L,
            message = editedMessage.copy(flags = Message.FLAG_REVOKED),
        )
        val hiddenConversationEventsBeforeRevoke = eventCount(hiddenUid, NotifyType.CONVERSATION_UPDATED)
        val hiddenMessageEventsBeforeRevoke = eventCount(hiddenUid, NotifyType.MESSAGE_RECV)
        val revokeResult = ctx.pgUnitOfWork.write { projectAndAppend(revoke, preview = "") }
        assertEquals(null, revokeResult.recipient(hiddenUid).conversation)
        assertEquals(
            hiddenConversationEventsBeforeRevoke,
            eventCount(hiddenUid, NotifyType.CONVERSATION_UPDATED),
        )
        assertEquals(hiddenMessageEventsBeforeRevoke + 1L, eventCount(hiddenUid, NotifyType.MESSAGE_RECV))
        assertConversationState(
            hiddenUid,
            fixture.chatId,
            lastSeq = 2L,
            hidden = true,
            lastMessageType = MessageType.REVOKE.code,
        )

        val newerCreate = fixture.createOperation(
            serverSeq = 3L,
            operationType = MessageOperationType.CREATE,
            revision = 1L,
            senderUid = sender,
            text = "genuinely newer",
        )
        val newerResult = ctx.pgUnitOfWork.write {
            projectAndAppend(newerCreate, preview = "genuinely newer")
        }
        assertEquals("genuinely newer", newerResult.recipient(hiddenUid).conversation?.lastMessage)
        assertConversationState(
            hiddenUid,
            fixture.chatId,
            lastSeq = 3L,
            hidden = false,
            lastMessage = "genuinely newer",
        )

        // 一旦 seq=3 成为当前，旧编辑仍会作为消息 revision 到达客户端，
        // 但不会为任何收件人发布过时的 Conversation 快照。
        val oldEdit = edit.copy(revision = 4L, message = editedMessage.copy(body = buildRichTextBody("old edit")))
        val conversationEventsBeforeOldEdit = fixture.memberUids.associateWith {
            eventCount(it, NotifyType.CONVERSATION_UPDATED)
        }
        val messageEventsBeforeOldEdit = fixture.memberUids.associateWith {
            eventCount(it, NotifyType.MESSAGE_RECV)
        }
        val oldEditResult = ctx.pgUnitOfWork.write { projectAndAppend(oldEdit, preview = "old edit") }
        assertTrue(oldEditResult.recipients.all { recipient -> recipient.conversation == null })
        assertEquals(
            conversationEventsBeforeOldEdit,
            fixture.memberUids.associateWith { eventCount(it, NotifyType.CONVERSATION_UPDATED) },
        )
        assertEquals(
            messageEventsBeforeOldEdit.mapValues { (_, count) -> count + 1L },
            fixture.memberUids.associateWith { eventCount(it, NotifyType.MESSAGE_RECV) },
        )

        // 不可变目标仍然包含全部三个用户，但投影会在持有成员行的同时
        // 将其与当前活动成员相交。被移除的用户收不到 seq=4 的消息或会话事件。
        transaction(ctx.database) {
            GroupMembers.update({
                (GroupMembers.chatId eq fixture.chatId) and (GroupMembers.uid eq removedUid)
            }) {
                it[GroupMembers.status] = 2
            }
        }
        val removedEventsBefore = eventSequences(removedUid)
        val postRemovalCreate = fixture.createOperation(
            serverSeq = 4L,
            operationType = MessageOperationType.CREATE,
            revision = 1L,
            senderUid = sender,
            text = "after removal",
        )
        val postRemovalResult = ctx.pgUnitOfWork.write {
            projectAndAppend(postRemovalCreate, preview = "after removal")
        }
        assertEquals(setOf(sender, hiddenUid), postRemovalResult.recipients.mapTo(linkedSetOf()) { it.uid })
        assertEquals(removedEventsBefore, eventSequences(removedUid))
        assertConversationState(removedUid, fixture.chatId, lastSeq = 3L, hidden = false)

        fixture.memberUids.forEach { uid ->
            val sequences = eventSequences(uid)
            assertEquals((1L..sequences.size.toLong()).toList(), sequences)
        }
    }

    @Test
    fun `overlapping chunked stream sets retain global lock order`() = runTest {
        // 生产短 uid 是大小写混合的 base62。这个夹具有意跨越
        // Kotlin/PostgreSQL 排序规则边界，同时跨越 512 uid 的 SQL 分块边界。
        val uids = List(OVERLAPPING_STREAM_UIDS) { index ->
            "${if (index % 2 == 0) 'A' else 'a'}${index.toString().padStart(7, '0')}"
        }.sorted()
        transaction(ctx.database) { insertFixtureUsers(uids, System.currentTimeMillis()) }
        val beforeFlush = CyclicBarrier(2)
        fun concurrentUnitOfWork() = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.BEFORE_EVENT_FLUSH) {
                    beforeFlush.await(5, TimeUnit.SECONDS)
                }
            },
        )

        val writes = listOf(
            async(Dispatchers.Default) {
                concurrentUnitOfWork().write {
                    uids.forEach { uid ->
                        appendEvent(
                            uid,
                            NotifyType.USER_UPDATED,
                            User(uid = uid, username = "batch-a", name = "Batch A"),
                        )
                    }
                }
            },
            async(Dispatchers.Default) {
                concurrentUnitOfWork().write {
                    uids.asReversed().forEach { uid ->
                        appendEvent(
                            uid,
                            NotifyType.USER_UPDATED,
                            User(uid = uid, username = "batch-b", name = "Batch B"),
                        )
                    }
                }
            },
        )
        withContext(Dispatchers.IO) {
            withTimeout(15_000) { writes.awaitAll() }
        }

        transaction(ctx.database) {
            val streams = SyncStreams.selectAll().where { SyncStreams.uid inList uids }.toList()
            assertEquals(OVERLAPPING_STREAM_UIDS, streams.size)
            assertTrue(streams.all { row -> row[SyncStreams.lastSeq] == 2L })
            val eventsByUid = SyncEvents.selectAll().where { SyncEvents.uid inList uids }
                .groupBy { row -> row[SyncEvents.uid] }
            assertEquals(OVERLAPPING_STREAM_UIDS, eventsByUid.size)
            assertTrue(eventsByUid.values.all { rows ->
                rows.map { row -> row[SyncEvents.streamSeq] }.sorted() == listOf(1L, 2L)
            })
        }
    }

    private fun PgWriteScope.projectAndAppend(
        operation: MessageProjectionOperation,
        preview: String? = PREVIEW,
    ): MessageProjectionApplyResult {
        val result = ctx.messageProjectionRepository.apply(transaction, operation, preview)
        if (!result.applied) return result
        result.recipients.forEach { recipient ->
            appendEvent(
                recipient.uid,
                NotifyType.MESSAGE_RECV,
                operation.message,
            )
            recipient.conversation?.let { conversation ->
                appendEvent(
                    recipient.uid,
                    NotifyType.CONVERSATION_UPDATED,
                    conversation,
                )
            }
        }
        return result
    }

    private fun createFullGroupFixture(): FullGroupFixture {
        return createGroupFixture(GroupPolicy.MAX_MEMBERS)
    }

    private fun createGroupFixture(memberCount: Int): FullGroupFixture {
        val memberUids = List(memberCount) { UUID.randomUUID().toString() }.sorted()
        val chatId = UUID.randomUUID().toString()
        val groupName = "Batch projection ${chatId.take(8)}"
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            insertFixtureUsers(memberUids, now)
            Chats.insert {
                it[Chats.chatId] = chatId
                it[Chats.chatType] = 2
                it[Chats.createdAt] = now
                it[Chats.updatedAt] = now
            }
            GroupChats.insert {
                it[GroupChats.chatId] = chatId
                it[GroupChats.name] = groupName
                it[GroupChats.creator] = memberUids.first()
                it[GroupChats.updatedAt] = now
            }
            GroupMembers.batchInsert(memberUids) { uid ->
                this[GroupMembers.chatId] = chatId
                this[GroupMembers.uid] = uid
                this[GroupMembers.role] = if (uid == memberUids.first()) 2 else 0
                this[GroupMembers.joinedAt] = now
            }
        }
        return FullGroupFixture(chatId, groupName, memberUids)
    }

    private fun FullGroupFixture.createOperation(): MessageProjectionOperation {
        return createOperation(
            serverSeq = 1L,
            operationType = MessageOperationType.CREATE,
            revision = 1L,
            senderUid = memberUids.first(),
            text = PREVIEW,
        )
    }

    private fun FullGroupFixture.createOperation(
        serverSeq: Long,
        operationType: MessageOperationType,
        revision: Long,
        senderUid: String,
        text: String,
    ): MessageProjectionOperation {
        val message = Message(
            chatId = chatId,
            clientMsgId = UUID.randomUUID().toString(),
            serverSeq = serverSeq,
            senderUid = senderUid,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = buildRichTextBody(text),
        )
        return MessageProjectionOperation(
            projectionKey = MessageProjectionOperation.stableKey(chatId, message.serverSeq),
            operation = operationType,
            revision = revision,
            message = message,
            target = MessageProjectionTarget(chatType = 2, recipientUids = memberUids),
        )
    }

    private fun Transaction.insertFixtureUsers(memberUids: List<String>, now: Long) {
        Users.batchInsert(memberUids) { uid ->
            this[Users.uid] = uid
            this[Users.username] = "batch-$uid"
            this[Users.name] = "Batch ${uid.take(8)}"
            this[Users.passwordHash] = "!batch-fixture"
            this[Users.createdAt] = now
            this[Users.updatedAt] = now
        }
    }

    private fun MessageProjectionApplyResult.recipient(uid: String) = recipients.single { it.uid == uid }

    private fun eventCount(uid: String, notifyType: NotifyType): Long = transaction(ctx.database) {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq notifyType.code)
        }.count()
    }

    private fun eventSequences(uid: String): List<Long> = transaction(ctx.database) {
        SyncEvents.selectAll().where { SyncEvents.uid eq uid }
            .map { row -> row[SyncEvents.streamSeq] }
            .sorted()
    }

    private fun assertConversationState(
        uid: String,
        chatId: String,
        lastSeq: Long,
        hidden: Boolean,
        lastMessage: String? = null,
        lastMessageType: Int? = null,
    ) {
        val row = transaction(ctx.database) {
            Conversations.selectAll().where {
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }.single()
        }
        assertEquals(lastSeq, row[Conversations.lastMsgSeq])
        assertEquals(hidden, row[Conversations.isHidden])
        if (lastMessage != null) assertEquals(lastMessage, row[Conversations.lastMessage])
        if (lastMessageType != null) assertEquals(lastMessageType, row[Conversations.lastMessageType])
    }

    private fun assertProjectionArtifacts(
        fixture: FullGroupFixture,
        operation: MessageProjectionOperation,
        expectedRowsPerTable: Int,
    ) {
        transaction(ctx.database) {
            assertEquals(
                if (expectedRowsPerTable == 0) 0L else 1L,
                ExternalProjectionReceipts.selectAll().where {
                    (ExternalProjectionReceipts.projectionKey eq operation.projectionKey) and
                        (ExternalProjectionReceipts.revision eq operation.revision)
                }.count(),
            )
            val conversations = Conversations.selectAll().where {
                Conversations.chatId eq fixture.chatId
            }.toList()
            assertEquals(expectedRowsPerTable, conversations.size)
            assertEquals(
                expectedRowsPerTable.toLong(),
                ConversationUsages.selectAll().where {
                    ConversationUsages.uid inList fixture.memberUids
                }.count(),
            )
            assertEquals(
                expectedRowsPerTable.toLong(),
                SyncStreams.selectAll().where { SyncStreams.uid inList fixture.memberUids }.count(),
            )
            assertEquals(
                (expectedRowsPerTable * EVENTS_PER_RECIPIENT).toLong(),
                SyncEvents.selectAll().where { SyncEvents.uid inList fixture.memberUids }.count(),
            )

            if (expectedRowsPerTable > 0) {
                val sender = conversations.single { row ->
                    row[Conversations.uid] == operation.message.senderUid
                }
                assertEquals(1L, sender[Conversations.readSeq])
                assertEquals(2L, sender[Conversations.version])
                assertEquals(PREVIEW, sender[Conversations.lastMessage])

                val regular = conversations.first { row ->
                    row[Conversations.uid] != operation.message.senderUid
                }
                assertEquals(0L, regular[Conversations.readSeq])
                assertEquals(1L, regular[Conversations.version])

                val usages = ConversationUsages.selectAll().where {
                    ConversationUsages.uid inList fixture.memberUids
                }.toList()
                assertTrue(usages.all { row -> row[ConversationUsages.conversationCount] == 1 })
                val streams = SyncStreams.selectAll().where {
                    SyncStreams.uid inList fixture.memberUids
                }.toList()
                assertTrue(streams.all { row -> row[SyncStreams.lastSeq] == EVENTS_PER_RECIPIENT.toLong() })

                val eventsByUid = SyncEvents.selectAll().where {
                    SyncEvents.uid inList fixture.memberUids
                }.groupBy { row -> row[SyncEvents.uid] }
                assertEquals(expectedRowsPerTable, eventsByUid.size)
                eventsByUid.values.forEach { rows ->
                    assertEquals(listOf(1L, 2L), rows.map { it[SyncEvents.streamSeq] }.sorted())
                    assertEquals(
                        setOf(NotifyType.MESSAGE_RECV.code, NotifyType.CONVERSATION_UPDATED.code),
                        rows.mapTo(linkedSetOf()) { it[SyncEvents.eventType] },
                    )
                }
            }
        }
    }

    private data class FullGroupFixture(
        val chatId: String,
        val groupName: String,
        val memberUids: List<String>,
    )

    private object InjectedBatchRollback : InjectedBatchRollbackException()
    private open class InjectedBatchRollbackException : RuntimeException("injected batched projection rollback")

    private data class ProjectionStatementShapes(
        var conversationUpserts: Int = 0,
        var usageInserts: Int = 0,
        var streamInserts: Int = 0,
        var eventInserts: Int = 0,
    ) {
        fun observe(sql: String) {
            val normalized = sql.lowercase()
            if ("insert into conversations" in normalized) conversationUpserts += 1
            if ("insert into conversation_usages" in normalized) usageInserts += 1
            if ("insert into sync_streams" in normalized) streamInserts += 1
            if ("insert into sync_events" in normalized) eventInserts += 1
        }
    }
}

private const val PREVIEW = "full group bounded projection"
private const val EVENTS_PER_RECIPIENT = 2
private const val MAX_FULL_GROUP_PROJECTION_STATEMENTS = 36
private const val OVERLAPPING_STREAM_UIDS = 600
