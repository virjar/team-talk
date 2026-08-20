package com.virjar.tk.integration

import com.virjar.tk.domain.contact.ContactService
import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.infra.db.PgUnitOfWorkStage
import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real PostgreSQL gates for the Contact aggregate + durable-event transaction boundary. */
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

        assertIs<InjectedContactRollbackException>(runCatching {
            service.accept(recipient, token)
        }.exceptionOrNull())
        assertFalse(ctx.contactRepo.isFriend(sender, recipient))
        assertFalse(ctx.contactRepo.isFriend(recipient, sender))
        assertEquals(ContactApplyRecord.STATUS_PENDING, ctx.contactRepo.getPendingApply(sender, recipient)?.status)
        assertEquals(
            acceptedEventsBefore,
            eventCount(sender, NotifyType.CONTACT_ACCEPTED) +
                eventCount(recipient, NotifyType.CONTACT_ACCEPTED),
        )

        ctx.contactService(recipient).accept(token)
        assertTrue(ctx.contactRepo.isFriend(sender, recipient))
        assertTrue(ctx.contactRepo.isFriend(recipient, sender))
        assertEquals(
            acceptedEventsBefore + 2L,
            eventCount(sender, NotifyType.CONTACT_ACCEPTED) +
                eventCount(recipient, NotifyType.CONTACT_ACCEPTED),
        )
    }

    @Test
    fun `add and delete use one outer transaction with no partial relationship or event`() = runTest {
        val first = ctx.registerUser(uniqueUsername("contact-uow-add-first"))
        val second = ctx.registerUser(uniqueUsername("contact-uow-add-second"))
        val store = ContactStore(ctx.contactRepo)

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
        assertIs<InjectedContactRollbackException>(runCatching {
            failing.reject(rejectRecipient, pendingToken(rejectRecipient, rejectSender))
        }.exceptionOrNull())
        assertEquals(
            ContactApplyRecord.STATUS_PENDING,
            ctx.contactRepo.getPendingApply(rejectSender, rejectRecipient)?.status,
        )

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
            ctx.contactRepo.addFriend(object : PgTransactionContext {}, first, second)
        }
        assertFalse(ctx.contactRepo.isFriend(first, second))
    }

    private fun contactService(unitOfWork: PgUnitOfWork): ContactService = ContactService(
        contactStore = ContactStore(ctx.contactRepo),
        unitOfWork = unitOfWork,
        users = UserStore(ctx.userRepo),
    )

    private fun failingUnitOfWork(): PgUnitOfWork = ExposedPgUnitOfWork(
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

    private fun eventCount(uid: String, notifyType: NotifyType): Long = transaction {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq notifyType.code)
        }.count()
    }

    private object InjectedContactRollback : InjectedContactRollbackException()
    private open class InjectedContactRollbackException : RuntimeException("injected contact rollback")
}
