package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.contact.ContactService
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.ContactDecisionReceipts
import com.virjar.tk.server.infra.db.FriendApplies
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.SyncStreams
import com.virjar.tk.server.infra.db.repository.ContactCapacityLimits
import com.virjar.tk.server.infra.db.repository.ExposedContactRepository
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.ContactApplyRecord
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 针对 Contact 聚合 + 持久事件事务边界的真实 PostgreSQL 门禁测试。 */
class ContactUnitOfWorkIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `apply and accept roll back relation state together with durable events`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("contact-uow-apply-sender"))
        val recipient = ctx.registerUser(uniqueUsername("contact-uow-apply-recipient"))
        val service = contactService(failingUnitOfWork())

        val applyEventsBefore = eventCount(recipient, NotifyType.CONTACT_APPLY)
        assertIs<InjectedContactRollbackException>(runCatching {
            service.apply(sender, recipient, "must roll back")
        }.exceptionOrNull())
        assertNull(ctx.contactRepo.getPendingApply(sender, recipient))
        assertEquals(applyEventsBefore, eventCount(recipient, NotifyType.CONTACT_APPLY))

        ctx.contactService(sender).apply(recipient, "committed")
        val token = pendingToken(recipient, sender)
        val acceptedEventsBefore = eventCount(sender, NotifyType.CONTACT_ACCEPTED) +
            eventCount(recipient, NotifyType.CONTACT_ACCEPTED)
        val decisionHintsBefore = eventCount(sender, NotifyType.CONTACT_APPLY) +
            eventCount(recipient, NotifyType.CONTACT_APPLY)
        val acceptOperationId = UUID.randomUUID().toString()

        assertIs<InjectedContactRollbackException>(runCatching {
            service.accept(recipient, acceptOperationId, System.currentTimeMillis(), token)
        }.exceptionOrNull())
        assertFalse(ctx.contactRepo.isFriend(sender, recipient))
        assertFalse(ctx.contactRepo.isFriend(recipient, sender))
        assertEquals(ContactApplyRecord.STATUS_PENDING, ctx.contactRepo.getPendingApply(sender, recipient)?.status)
        assertEquals(
            acceptedEventsBefore,
            eventCount(sender, NotifyType.CONTACT_ACCEPTED) +
                eventCount(recipient, NotifyType.CONTACT_ACCEPTED),
        )
        assertEquals(
            decisionHintsBefore,
            eventCount(sender, NotifyType.CONTACT_APPLY) + eventCount(recipient, NotifyType.CONTACT_APPLY),
        )
        assertTrue(transaction(ctx.database) {
            ContactDecisionReceipts.selectAll().where {
                (ContactDecisionReceipts.actorUid eq recipient) and
                    (ContactDecisionReceipts.operationId eq acceptOperationId)
            }.empty()
        })

        ctx.contactService(recipient).accept(token)
        assertTrue(ctx.contactRepo.isFriend(sender, recipient))
        assertTrue(ctx.contactRepo.isFriend(recipient, sender))
        assertEquals(
            acceptedEventsBefore + 2L,
            eventCount(sender, NotifyType.CONTACT_ACCEPTED) +
                eventCount(recipient, NotifyType.CONTACT_ACCEPTED),
        )
        assertEquals(
            decisionHintsBefore + 2L,
            eventCount(sender, NotifyType.CONTACT_APPLY) + eventCount(recipient, NotifyType.CONTACT_APPLY),
        )
    }

    @Test
    fun `add and delete use one outer transaction with no partial relationship or event`() = runTest {
        val first = ctx.registerUser(uniqueUsername("contact-uow-add-first"))
        val second = ctx.registerUser(uniqueUsername("contact-uow-add-second"))
        val store = ctx.contactRepo

        assertIs<InjectedContactRollbackException>(runCatching {
            failingUnitOfWork().write {
                store.addFriend(transaction, first, second)
                appendEvent(first, NotifyType.CONTACT_ACCEPTED, Contact(uid = first, friendUid = second))
                appendEvent(second, NotifyType.CONTACT_ACCEPTED, Contact(uid = second, friendUid = first))
            }
        }.exceptionOrNull())
        assertFalse(ctx.contactRepo.isFriend(first, second))
        assertFalse(ctx.contactRepo.isFriend(second, first))

        ctx.pgUnitOfWork.write {
            store.addFriend(transaction, first, second)
        }
        assertTrue(ctx.contactRepo.isFriend(first, second))
        assertTrue(ctx.contactRepo.isFriend(second, first))

        val deletedEventsBefore = eventCount(first, NotifyType.CONTACT_DELETED) +
            eventCount(second, NotifyType.CONTACT_DELETED)
        assertIs<InjectedContactRollbackException>(runCatching {
            contactService(failingUnitOfWork()).delete(first, second)
        }.exceptionOrNull())
        assertTrue(ctx.contactRepo.isFriend(first, second))
        assertTrue(ctx.contactRepo.isFriend(second, first))
        assertEquals(
            deletedEventsBefore,
            eventCount(first, NotifyType.CONTACT_DELETED) + eventCount(second, NotifyType.CONTACT_DELETED),
        )
    }

    @Test
    fun `delete advances only views that leave an active friendship`() = runTest {
        val actor = ctx.registerUser(uniqueUsername("contact-delete-idempotent-actor"))
        val target = ctx.registerUser(uniqueUsername("contact-delete-idempotent-target"))
        val stranger = ctx.registerUser(uniqueUsername("contact-delete-idempotent-stranger"))
        establishFriendship(actor, target)

        val actorBefore = streamPosition(actor)
        val targetBefore = streamPosition(target)
        ctx.contactService(actor).delete(target)

        assertEquals(actorBefore + 1L, streamPosition(actor))
        assertEquals(targetBefore + 1L, streamPosition(target))
        assertDeletedContact(actor, target, actorBefore)
        assertDeletedContact(target, actor, targetBefore)

        val actorAfterFirstDelete = streamPosition(actor)
        val targetAfterFirstDelete = streamPosition(target)
        ctx.contactService(actor).delete(target)
        assertEquals(actorAfterFirstDelete, streamPosition(actor), "retry must not allocate an eventId")
        assertEquals(targetAfterFirstDelete, streamPosition(target), "retry must not allocate an eventId")

        val strangerBefore = streamPosition(stranger)
        ctx.contactService(actor).delete(stranger)
        assertEquals(actorAfterFirstDelete, streamPosition(actor), "deleting a stranger is a no-op")
        assertEquals(strangerBefore, streamPosition(stranger), "a stranger's stream must remain untouched")
    }

    @Test
    fun `blacklist notifies only friendship or pending projections that actually exit`() = runTest {
        val actor = ctx.registerUser(uniqueUsername("contact-blacklist-friend-actor"))
        val target = ctx.registerUser(uniqueUsername("contact-blacklist-friend-target"))
        establishFriendship(actor, target)

        val actorBefore = streamPosition(actor)
        val targetBefore = streamPosition(target)
        ctx.contactService(actor).blacklist(target)
        assertEquals(actorBefore + 1L, streamPosition(actor))
        assertEquals(targetBefore + 1L, streamPosition(target))
        assertDeletedContact(actor, target, actorBefore)
        assertDeletedContact(target, actor, targetBefore)
        assertTrue(ctx.contactRepo.isBlocked(actor, target))

        val actorAfterFirstBlock = streamPosition(actor)
        val targetAfterFirstBlock = streamPosition(target)
        ctx.contactService(actor).blacklist(target)
        assertEquals(actorAfterFirstBlock, streamPosition(actor), "repeated blacklist must be event-idempotent")
        assertEquals(targetAfterFirstBlock, streamPosition(target), "repeated blacklist must be event-idempotent")

        val strangerActor = ctx.registerUser(uniqueUsername("contact-blacklist-stranger-actor"))
        val strangerTarget = ctx.registerUser(uniqueUsername("contact-blacklist-stranger-target"))
        val strangerActorBefore = streamPosition(strangerActor)
        val strangerTargetBefore = streamPosition(strangerTarget)
        ctx.contactService(strangerActor).blacklist(strangerTarget)
        assertTrue(ctx.contactRepo.isBlocked(strangerActor, strangerTarget))
        assertEquals(strangerActorBefore, streamPosition(strangerActor))
        assertEquals(strangerTargetBefore, streamPosition(strangerTarget))
        ctx.contactService(strangerActor).blacklist(strangerTarget)
        assertEquals(strangerActorBefore, streamPosition(strangerActor))
        assertEquals(strangerTargetBefore, streamPosition(strangerTarget))

        val sender = ctx.registerUser(uniqueUsername("contact-blacklist-pending-sender"))
        val recipient = ctx.registerUser(uniqueUsername("contact-blacklist-pending-recipient"))
        ctx.contactService(sender).apply(recipient, "pending")
        val senderBeforePendingClose = streamPosition(sender)
        val recipientBeforePendingClose = streamPosition(recipient)
        ctx.contactService(recipient).blacklist(sender)
        assertEquals(senderBeforePendingClose + 1L, streamPosition(sender))
        assertEquals(recipientBeforePendingClose + 1L, streamPosition(recipient))
        assertDeletedContact(sender, recipient, senderBeforePendingClose)
        assertDeletedContact(recipient, sender, recipientBeforePendingClose)
        assertNull(ctx.contactRepo.getPendingApply(sender, recipient))

        val senderAfterPendingClose = streamPosition(sender)
        val recipientAfterPendingClose = streamPosition(recipient)
        ctx.contactService(recipient).blacklist(sender)
        assertEquals(senderAfterPendingClose, streamPosition(sender))
        assertEquals(recipientAfterPendingClose, streamPosition(recipient))
    }

    @Test
    fun `blacklist pending exit and its event ids roll back together`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("contact-blacklist-rollback-sender"))
        val recipient = ctx.registerUser(uniqueUsername("contact-blacklist-rollback-recipient"))
        ctx.contactService(sender).apply(recipient, "must remain pending")
        val senderBefore = streamPosition(sender)
        val recipientBefore = streamPosition(recipient)

        assertIs<InjectedContactRollbackException>(runCatching {
            contactService(failingUnitOfWork()).blacklist(recipient, sender)
        }.exceptionOrNull())

        assertFalse(ctx.contactRepo.isBlocked(recipient, sender))
        assertEquals(
            ContactApplyRecord.STATUS_PENDING,
            ctx.contactRepo.getPendingApply(sender, recipient)?.status,
        )
        assertEquals(senderBefore, streamPosition(sender))
        assertEquals(recipientBefore, streamPosition(recipient))
    }

    @Test
    fun `blacklist and non notifying mutations also roll back at the aggregate boundary`() = runTest {
        val first = ctx.registerUser(uniqueUsername("contact-uow-mutations-first"))
        val second = ctx.registerUser(uniqueUsername("contact-uow-mutations-second"))
        establishFriendship(first, second)
        ctx.contactService(first).setRemark(second, "before")

        val failing = contactService(failingUnitOfWork())
        val deletedEventsBefore = eventCount(first, NotifyType.CONTACT_DELETED) +
            eventCount(second, NotifyType.CONTACT_DELETED)
        assertIs<InjectedContactRollbackException>(runCatching {
            failing.blacklist(first, second)
        }.exceptionOrNull())
        assertTrue(ctx.contactRepo.isFriend(first, second))
        assertTrue(ctx.contactRepo.isFriend(second, first))
        assertFalse(ctx.contactRepo.isBlocked(first, second))
        assertEquals(
            deletedEventsBefore,
            eventCount(first, NotifyType.CONTACT_DELETED) + eventCount(second, NotifyType.CONTACT_DELETED),
        )

        assertIs<InjectedContactRollbackException>(runCatching {
            failing.setRemark(first, second, "after")
        }.exceptionOrNull())
        assertEquals("before", ctx.contactRepo.listFriends(first).single { it.friendUid == second }.remark)

        val rejectSender = ctx.registerUser(uniqueUsername("contact-uow-reject-sender"))
        val rejectRecipient = ctx.registerUser(uniqueUsername("contact-uow-reject-recipient"))
        ctx.contactService(rejectSender).apply(rejectRecipient, null)
        val rejectOperationId = UUID.randomUUID().toString()
        assertIs<InjectedContactRollbackException>(runCatching {
            failing.reject(
                rejectRecipient,
                rejectOperationId,
                System.currentTimeMillis(),
                pendingToken(rejectRecipient, rejectSender),
            )
        }.exceptionOrNull())
        assertEquals(
            ContactApplyRecord.STATUS_PENDING,
            ctx.contactRepo.getPendingApply(rejectSender, rejectRecipient)?.status,
        )
        assertTrue(transaction(ctx.database) {
            ContactDecisionReceipts.selectAll().where {
                (ContactDecisionReceipts.actorUid eq rejectRecipient) and
                    (ContactDecisionReceipts.operationId eq rejectOperationId)
            }.empty()
        })

        val blocked = ctx.registerUser(uniqueUsername("contact-uow-remove-blocked"))
        ctx.contactService(first).blacklist(blocked)
        assertIs<InjectedContactRollbackException>(runCatching {
            failing.removeFromBlacklist(first, blocked)
        }.exceptionOrNull())
        assertTrue(ctx.contactRepo.isBlocked(first, blocked))
    }

    @Test
    fun `contact repository rejects a foreign or standalone transaction handle`() = runTest {
        val first = ctx.registerUser(uniqueUsername("contact-uow-context-first"))
        val second = ctx.registerUser(uniqueUsername("contact-uow-context-second"))

        assertFailsWith<IllegalStateException> {
            ctx.contactRepo.addFriend(object : PgWriteTransactionContext {}, first, second)
        }
        assertFalse(ctx.contactRepo.isFriend(first, second))
    }

    @Test
    fun `contact and blacklist capacity fences reject partial aggregate writes`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("contact-capacity-owner"))
        val friend = ctx.registerUser(uniqueUsername("contact-capacity-friend"))
        val extraFriend = ctx.registerUser(uniqueUsername("contact-capacity-extra"))
        val blocked = ctx.registerUser(uniqueUsername("contact-capacity-blocked"))
        val extraBlocked = ctx.registerUser(uniqueUsername("contact-capacity-extra-blocked"))
        val repository = ExposedContactRepository(
            database = ctx.database,
            userRepo = ctx.userRepo,
            capacity = ContactCapacityLimits(friendsPerUser = 1, blacklistEntriesPerUser = 1),
        )
        ctx.pgUnitOfWork.write { repository.addFriend(transaction, owner, friend) }
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write { repository.addFriend(transaction, owner, extraFriend) }
        }
        assertTrue(repository.isFriend(owner, friend))
        assertTrue(repository.isFriend(friend, owner))
        assertFalse(repository.isFriend(owner, extraFriend))
        assertFalse(repository.isFriend(extraFriend, owner), "reciprocal insert must roll back too")

        val limitedService = ContactService(repository, ctx.pgUnitOfWork, ctx.userRepo)
        assertFailsWith<IllegalArgumentException> { limitedService.apply(owner, extraFriend, null) }
        assertNull(repository.getPendingApply(owner, extraFriend))

        ctx.pgUnitOfWork.write { repository.blacklist(transaction, owner, blocked) }
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write { repository.blacklist(transaction, owner, extraBlocked) }
        }
        assertTrue(repository.isBlocked(owner, blocked))
        assertFalse(repository.isBlocked(owner, extraBlocked))
        assertEquals(listOf(blocked), repository.listBlacklist(owner).map { it.friendUid })
    }

    @Test
    fun `pending apply sender and recipient budgets serialize concurrent pairs and release on terminal state`() =
        runTest {
            val repository = ExposedContactRepository(
                database = ctx.database,
                userRepo = ctx.userRepo,
                capacity = ContactCapacityLimits(
                    outgoingPendingAppliesPerUser = 1,
                    incomingPendingAppliesPerUser = 1,
                    terminalApplyRecordsPerUser = 8,
                ),
            )
            val service = ContactService(repository, ctx.pgUnitOfWork, ctx.userRepo)

            val sender = ctx.registerUser(uniqueUsername("apply-sender-budget"))
            val outgoingTargets = List(2) { index ->
                ctx.registerUser(uniqueUsername("apply-outgoing-target-$index"))
            }
            val outgoingAttempts = coroutineScope {
                outgoingTargets.map { target ->
                    async(Dispatchers.Default) { target to runCatching { service.apply(sender, target, null) } }
                }.awaitAll()
            }
            assertEquals(1, outgoingAttempts.count { it.second.isSuccess })
            assertEquals(1, outgoingAttempts.count { it.second.isFailure })
            val admittedOutgoing = outgoingAttempts.single { it.second.isSuccess }.first
            val rejectedOutgoing = outgoingAttempts.single { it.second.isFailure }.first
            assertEquals(
                "发出的待处理好友申请数量已达上限",
                outgoingAttempts.single { it.second.isFailure }.second.exceptionOrNull()?.message,
            )

            service.reject(admittedOutgoing, repository.listPendingApplies(admittedOutgoing).single().token!!)
            service.apply(sender, rejectedOutgoing, "released outgoing slot")
            assertEquals(listOf(sender), repository.listPendingApplies(rejectedOutgoing).map { it.fromUid })

            val recipient = ctx.registerUser(uniqueUsername("apply-recipient-budget"))
            val incomingSenders = List(2) { index ->
                ctx.registerUser(uniqueUsername("apply-incoming-sender-$index"))
            }
            val incomingAttempts = coroutineScope {
                incomingSenders.map { candidate ->
                    async(Dispatchers.Default) { candidate to runCatching { service.apply(candidate, recipient, null) } }
                }.awaitAll()
            }
            assertEquals(1, incomingAttempts.count { it.second.isSuccess })
            assertEquals(1, incomingAttempts.count { it.second.isFailure })
            assertEquals(
                "收到的待处理好友申请数量已达上限",
                incomingAttempts.single { it.second.isFailure }.second.exceptionOrNull()?.message,
            )
            val admittedIncoming = incomingAttempts.single { it.second.isSuccess }.first
            val rejectedIncoming = incomingAttempts.single { it.second.isFailure }.first
            val exactInbox = repository.listPendingApplies(recipient)
            assertEquals(1, exactInbox.size, "the pending view must return the complete admitted inbox")
            assertEquals(admittedIncoming, exactInbox.single().fromUid)

            service.reject(recipient, exactInbox.single().token!!)
            service.apply(rejectedIncoming, recipient, "released incoming slot")
            assertEquals(listOf(rejectedIncoming), repository.listPendingApplies(recipient).map { it.fromUid })
        }

    @Test
    fun `terminal apply history is shared bounded and erases processing capabilities`() = runTest {
        val repository = ExposedContactRepository(
            database = ctx.database,
            userRepo = ctx.userRepo,
            capacity = ContactCapacityLimits(
                outgoingPendingAppliesPerUser = 1,
                incomingPendingAppliesPerUser = 1,
                terminalApplyRecordsPerUser = 2,
            ),
        )
        val service = ContactService(repository, ctx.pgUnitOfWork, ctx.userRepo)
        val sender = ctx.registerUser(uniqueUsername("terminal-history-sender"))
        val requestIds = mutableListOf<Long>()

        repeat(3) { index ->
            val recipient = ctx.registerUser(uniqueUsername("terminal-history-recipient-$index"))
            requestIds += service.apply(sender, recipient, "request-$index").id
            service.reject(recipient, repository.listPendingApplies(recipient).single().token!!)
        }

        val retained = repository.listApplyRecords(sender, beforeId = 0, limit = 100)
        assertEquals(requestIds.takeLast(2).reversed(), retained.map { it.id })
        assertTrue(retained.all { it.status == ContactApplyRecord.STATUS_REJECTED && it.token == null })
        transaction(ctx.database) {
            val rows = FriendApplies.selectAll().where {
                (FriendApplies.fromUid eq sender) or (FriendApplies.toUid eq sender)
            }.toList()
            assertEquals(2, rows.size)
            assertTrue(rows.all { it[FriendApplies.token] == null })
        }
    }

    private fun contactService(unitOfWork: PgUnitOfWork): ContactService = ContactService(
        contacts = ctx.contactRepo,
        unitOfWork = unitOfWork,
        users = ctx.userRepo,
    )

    private fun failingUnitOfWork(): PgUnitOfWork = ExposedPgUnitOfWork(
        database = ctx.database,
        onEventsCommitted = {},
        hooks = PgUnitOfWorkHooks { stage ->
            if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                throw InjectedContactRollback
            }
        },
    )

    private suspend fun establishFriendship(first: String, second: String) {
        ctx.contactService(first).apply(second, null)
        ctx.contactService(second).accept(pendingToken(second, first))
    }

    private suspend fun pendingToken(recipientUid: String, senderUid: String): String =
        ctx.contactService(recipientUid).listPendingApplies()
            .single { it.fromUid == senderUid }
            .token!!

    private fun eventCount(uid: String, notifyType: NotifyType): Long = transaction(ctx.database) {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq notifyType.code)
        }.count()
    }

    private fun streamPosition(uid: String): Long = transaction(ctx.database) {
        SyncStreams.selectAll().where { SyncStreams.uid eq uid }
            .singleOrNull()
            ?.get(SyncStreams.lastSeq)
            ?: 0L
    }

    private fun assertDeletedContact(uid: String, friendUid: String, afterEventId: Long) {
        val event = ctx.syncEventReader.getEventsAfter(uid, afterEventId, 10).single()
        assertEquals(NotifyType.CONTACT_DELETED.code, event.notifyType)
        val contact = ProtoCodec.decode(Contact, requireNotNull(event.payload))
        assertEquals(uid, contact.uid)
        assertEquals(friendUid, contact.friendUid)
    }

    private object InjectedContactRollback : InjectedContactRollbackException()
    private open class InjectedContactRollbackException : RuntimeException("injected contact rollback")
}
