package com.virjar.tk.integration

import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.infra.db.PgUnitOfWorkStage
import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.infra.db.Users
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SortOrder
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

/** Real PostgreSQL coverage for group membership + projection + durable-event atomicity. */
class ChatMemberUnitOfWorkIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `member add commits conversation and recipient events with the membership`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("add-owner"))
        val observer = ctx.registerUser(uniqueUsername("add-observer"))
        val target = ctx.registerUser(uniqueUsername("add-target"))
        val group = ctx.chatService.createGroup("Add events", null, owner, listOf(observer))
        val baselines = listOf(owner, observer, target).associateWith(::latestEventSeq)

        ctx.chatService.addMembers(owner, group.chatId, listOf(target))

        assertEquals(
            listOf(NotifyType.CHAT_CREATED, NotifyType.MEMBER_ADDED),
            eventTypesAfter(target, baselines.getValue(target)),
        )
        assertEquals(listOf(NotifyType.MEMBER_ADDED), eventTypesAfter(owner, baselines.getValue(owner)))
        assertEquals(listOf(NotifyType.MEMBER_ADDED), eventTypesAfter(observer, baselines.getValue(observer)))
        assertTrue(isActiveMember(group.chatId, target))
        assertTrue(hasConversation(group.chatId, target))
    }

    @Test
    fun `member add event failure rolls back membership and conversation`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("add-rollback-owner"))
        val observer = ctx.registerUser(uniqueUsername("add-rollback-observer"))
        val target = ctx.registerUser(uniqueUsername("add-rollback-target"))
        val group = ctx.chatService.createGroup("Add rollback", null, owner, listOf(observer))
        val baselines = listOf(owner, observer, target).associateWith(::latestEventSeq)
        val failingService = ctx.freshChatService(
            ExposedPgUnitOfWork(
                onEventsCommitted = {},
                hooks = PgUnitOfWorkHooks { stage ->
                    if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                        throw InjectedMemberRemovalRollback
                    }
                },
            ),
        )

        assertIs<InjectedMemberRemovalRollbackException>(
            runCatching { failingService.addMembers(owner, group.chatId, listOf(target)) }.exceptionOrNull(),
        )

        assertTrue(!isActiveMember(group.chatId, target))
        assertTrue(!hasConversation(group.chatId, target))
        listOf(owner, observer, target).forEach { uid ->
            assertTrue(eventTypesAfter(uid, baselines.getValue(uid)).isEmpty())
        }
    }

    @Test
    fun `member add rejects a stale cached admin under the locked snapshot`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("add-locked-owner"))
        val staleAdmin = ctx.registerUser(uniqueUsername("add-locked-admin"))
        val target = ctx.registerUser(uniqueUsername("add-locked-target"))
        val group = ctx.chatService.createGroup("Add locked permission", null, owner, listOf(staleAdmin))
        ctx.chatService.setRole(owner, group.chatId, staleAdmin, 1)
        assertEquals(1, ctx.chatService.getMembers(group.chatId).single { it.uid == staleAdmin }.role)
        transaction {
            GroupMembers.update({
                (GroupMembers.chatId eq group.chatId) and (GroupMembers.uid eq staleAdmin)
            }) { it[GroupMembers.role] = 0 }
        }
        val baselines = listOf(owner, staleAdmin, target).associateWith(::latestEventSeq)

        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.addMembers(staleAdmin, group.chatId, listOf(target))
        }

        assertTrue(!isActiveMember(group.chatId, target))
        assertTrue(!hasConversation(group.chatId, target))
        listOf(owner, staleAdmin, target).forEach { uid ->
            assertTrue(eventTypesAfter(uid, baselines.getValue(uid)).isEmpty())
        }
    }

    @Test
    fun `invite join commits quota membership conversation and events together`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("invite-owner"))
        val observer = ctx.registerUser(uniqueUsername("invite-observer"))
        val joiner = ctx.registerUser(uniqueUsername("invite-joiner"))
        val group = ctx.chatService.createGroup("Invite atomic", null, owner, listOf(observer))
        val token = ctx.chatService.createInviteLink(owner, group.chatId, "atomic", 1, 0)
        val baselines = listOf(owner, observer, joiner).associateWith(::latestEventSeq)

        ctx.chatService.joinByInvite(joiner, token)

        assertEquals(listOf(NotifyType.CHAT_CREATED), eventTypesAfter(owner, baselines.getValue(owner)))
        assertEquals(listOf(NotifyType.CHAT_CREATED), eventTypesAfter(observer, baselines.getValue(observer)))
        assertEquals(listOf(NotifyType.CHAT_CREATED), eventTypesAfter(joiner, baselines.getValue(joiner)))
        assertTrue(isActiveMember(group.chatId, joiner))
        assertTrue(hasConversation(group.chatId, joiner))
        assertEquals(1, ctx.chatService.listInviteLinks(owner, group.chatId).single { it.token == token }.useCount)
    }

    @Test
    fun `duplicate invite join is an event and quota no-op`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("invite-idempotent-owner"))
        val observer = ctx.registerUser(uniqueUsername("invite-idempotent-observer"))
        val joiner = ctx.registerUser(uniqueUsername("invite-idempotent-joiner"))
        val group = ctx.chatService.createGroup("Invite idempotent", null, owner, listOf(observer))
        val token = ctx.chatService.createInviteLink(owner, group.chatId, "idempotent", 1, 0)
        ctx.chatService.joinByInvite(joiner, token)
        val baselines = listOf(owner, observer, joiner).associateWith(::latestEventSeq)

        ctx.chatService.joinByInvite(joiner, token)

        assertEquals(1, ctx.chatService.listInviteLinks(owner, group.chatId).single { it.token == token }.useCount)
        listOf(owner, observer, joiner).forEach { uid ->
            assertTrue(eventTypesAfter(uid, baselines.getValue(uid)).isEmpty())
        }
        assertTrue(isActiveMember(group.chatId, joiner))
        assertTrue(hasConversation(group.chatId, joiner))
    }

    @Test
    fun `invite join event failure rolls back quota membership and conversation`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("invite-rollback-owner"))
        val observer = ctx.registerUser(uniqueUsername("invite-rollback-observer"))
        val joiner = ctx.registerUser(uniqueUsername("invite-rollback-joiner"))
        val group = ctx.chatService.createGroup("Invite rollback", null, owner, listOf(observer))
        val token = ctx.chatService.createInviteLink(owner, group.chatId, "rollback", 1, 0)
        val baselines = listOf(owner, observer, joiner).associateWith(::latestEventSeq)
        val failingService = ctx.freshChatService(
            ExposedPgUnitOfWork(
                onEventsCommitted = {},
                hooks = PgUnitOfWorkHooks { stage ->
                    if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                        throw InjectedMemberRemovalRollback
                    }
                },
            ),
        )

        assertIs<InjectedMemberRemovalRollbackException>(
            runCatching { failingService.joinByInvite(joiner, token) }.exceptionOrNull(),
        )

        assertTrue(!isActiveMember(group.chatId, joiner))
        assertTrue(!hasConversation(group.chatId, joiner))
        assertEquals(0, ctx.chatService.listInviteLinks(owner, group.chatId).single { it.token == token }.useCount)
        listOf(owner, observer, joiner).forEach { uid ->
            assertTrue(eventTypesAfter(uid, baselines.getValue(uid)).isEmpty())
        }
    }

    @Test
    fun `kick sends deletion only to target and member removal only to remaining users`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("kick-owner"))
        val target = ctx.registerUser(uniqueUsername("kick-target"))
        val observer = ctx.registerUser(uniqueUsername("kick-observer"))
        val group = ctx.chatService.createGroup("Kick events", null, owner, listOf(target, observer))
        val baselines = listOf(owner, target, observer).associateWith(::latestEventSeq)

        ctx.chatService.removeMember(owner, group.chatId, target)

        assertEquals(
            listOf(NotifyType.CHAT_DELETED, NotifyType.CONVERSATION_DELETED),
            eventTypesAfter(target, baselines.getValue(target)),
        )
        assertEquals(
            listOf(NotifyType.MEMBER_REMOVED),
            eventTypesAfter(owner, baselines.getValue(owner)),
        )
        assertEquals(
            listOf(NotifyType.MEMBER_REMOVED),
            eventTypesAfter(observer, baselines.getValue(observer)),
        )
        assertTrue(eventTypesAfter(target, baselines.getValue(target)).none { it == NotifyType.MEMBER_REMOVED })
        assertTrue(ctx.chatService.getMembers(group.chatId).none { it.uid == target })
        assertTrue(!isActiveMember(group.chatId, target))
        assertTrue(!hasConversation(group.chatId, target))
        assertEquals(null, ctx.conversationRepo.getConversation(target, group.chatId))
    }

    @Test
    fun `owner can atomically remove a banned human and every durable projection`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("ban-remove-owner"))
        val target = ctx.registerUser(uniqueUsername("ban-remove-target"))
        val observer = ctx.registerUser(uniqueUsername("ban-remove-observer"))
        val group = ctx.chatService.createGroup("Ban removal", null, owner, listOf(target, observer))
        val baselines = listOf(owner, target, observer).associateWith(::latestEventSeq)
        transaction {
            Users.update({ Users.uid eq target }) { it[Users.status] = 2 }
        }

        ctx.chatService.removeMember(owner, group.chatId, target)

        assertTrue(!isActiveMember(group.chatId, target))
        assertTrue(!hasConversation(group.chatId, target))
        assertEquals(
            listOf(NotifyType.CHAT_DELETED, NotifyType.CONVERSATION_DELETED),
            eventTypesAfter(target, baselines.getValue(target)),
        )
        listOf(owner, observer).forEach { uid ->
            assertEquals(
                listOf(NotifyType.MEMBER_REMOVED),
                eventTypesAfter(uid, baselines.getValue(uid)),
            )
        }
    }

    @Test
    fun `self leave has the same per-user tombstone contract`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("leave-owner"))
        val leaver = ctx.registerUser(uniqueUsername("leave-target"))
        val observer = ctx.registerUser(uniqueUsername("leave-observer"))
        val group = ctx.chatService.createGroup("Leave events", null, owner, listOf(leaver, observer))
        val baselines = listOf(owner, leaver, observer).associateWith(::latestEventSeq)

        ctx.chatService.leaveGroup(leaver, group.chatId)

        assertEquals(
            listOf(NotifyType.CHAT_DELETED, NotifyType.CONVERSATION_DELETED),
            eventTypesAfter(leaver, baselines.getValue(leaver)),
        )
        assertEquals(listOf(NotifyType.MEMBER_REMOVED), eventTypesAfter(owner, baselines.getValue(owner)))
        assertEquals(listOf(NotifyType.MEMBER_REMOVED), eventTypesAfter(observer, baselines.getValue(observer)))
        assertTrue(!isActiveMember(group.chatId, leaver))
        assertTrue(!hasConversation(group.chatId, leaver))
    }

    @Test
    fun `failure after event flush rolls back membership conversation and every event`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("rollback-owner"))
        val target = ctx.registerUser(uniqueUsername("rollback-target"))
        val observer = ctx.registerUser(uniqueUsername("rollback-observer"))
        val group = ctx.chatService.createGroup("Rollback removal", null, owner, listOf(target, observer))
        // Warm the process-local cache. A failed transaction must not publish invalidation either.
        assertNotNull(ctx.chatService.getMembers(group.chatId).singleOrNull { it.uid == target })
        val baselines = listOf(owner, target, observer).associateWith(::latestEventSeq)
        val failingService = ctx.freshChatService(
            ExposedPgUnitOfWork(
                onEventsCommitted = {},
                hooks = PgUnitOfWorkHooks { stage ->
                    if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                        throw InjectedMemberRemovalRollback
                    }
                },
            ),
        )

        assertIs<InjectedMemberRemovalRollbackException>(
            runCatching { failingService.removeMember(owner, group.chatId, target) }.exceptionOrNull(),
        )

        assertTrue(isActiveMember(group.chatId, target))
        assertTrue(hasConversation(group.chatId, target))
        assertNotNull(ctx.chatService.getMembers(group.chatId).singleOrNull { it.uid == target })
        listOf(owner, target, observer).forEach { uid ->
            assertTrue(eventTypesAfter(uid, baselines.getValue(uid)).isEmpty())
        }
    }

    @Test
    fun `locked membership facts reject a stale cached admin permission`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("locked-owner"))
        val staleAdmin = ctx.registerUser(uniqueUsername("locked-stale-admin"))
        val target = ctx.registerUser(uniqueUsername("locked-target"))
        val group = ctx.chatService.createGroup("Locked permissions", null, owner, listOf(staleAdmin, target))
        ctx.chatService.setRole(owner, group.chatId, staleAdmin, 1)
        // Load role=admin into ChatStore, then simulate another server process committing a demotion
        // without touching this process's cache.
        assertEquals(1, ctx.chatService.getMembers(group.chatId).single { it.uid == staleAdmin }.role)
        transaction {
            GroupMembers.update({
                (GroupMembers.chatId eq group.chatId) and (GroupMembers.uid eq staleAdmin)
            }) { it[GroupMembers.role] = 0 }
        }
        val baselines = listOf(owner, staleAdmin, target).associateWith(::latestEventSeq)

        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.removeMember(staleAdmin, group.chatId, target)
        }

        assertTrue(isActiveMember(group.chatId, target))
        assertTrue(hasConversation(group.chatId, target))
        listOf(owner, staleAdmin, target).forEach { uid ->
            assertTrue(eventTypesAfter(uid, baselines.getValue(uid)).isEmpty())
        }
    }

    private fun latestEventSeq(uid: String): Long = transaction {
        SyncEvents.selectAll().where { SyncEvents.uid eq uid }
            .orderBy(SyncEvents.streamSeq, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(SyncEvents.streamSeq)
            ?: 0L
    }

    private fun eventTypesAfter(uid: String, afterSeq: Long): List<NotifyType> = transaction {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.streamSeq greater afterSeq)
        }.orderBy(SyncEvents.streamSeq, SortOrder.ASC)
            .map { row -> NotifyType.fromCode(row[SyncEvents.eventType]) }
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

    private object InjectedMemberRemovalRollback : InjectedMemberRemovalRollbackException()
    private open class InjectedMemberRemovalRollbackException : RuntimeException("injected member removal rollback")
}
