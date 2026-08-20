package com.virjar.tk.integration

import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.infra.db.PgUnitOfWorkStage
import com.virjar.tk.infra.db.SyncEvents
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
