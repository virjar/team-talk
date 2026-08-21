package com.virjar.tk.integration

import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.infra.db.GroupMemberMutes
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.infra.db.PgUnitOfWorkStage
import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.repository.ChatRepositoryHooks
import com.virjar.tk.infra.db.repository.ChatRepositoryStage
import com.virjar.tk.infra.db.repository.ExposedChatRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PostgreSQL coverage for the remaining Chat command + durable-event commit boundaries. */
class ChatWriteUnitOfWorkIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `chat creation event failure rolls back chat memberships and conversations`() = runTest {
        val first = ctx.registerUser(uniqueUsername("create-uow-first"))
        val second = ctx.registerUser(uniqueUsername("create-uow-second"))
        val beforeChats = transaction { Chats.selectAll().count() }
        val beforeConversations = conversationCount(first, second)
        val baselines = listOf(first, second).associateWith(::latestEventSeq)
        val failing = ctx.freshChatService(failingUnitOfWork())

        assertIs<InjectedChatWriteRollbackException>(
            runCatching { failing.createPersonalChat(first, second) }.exceptionOrNull(),
        )
        assertEquals(beforeChats, transaction { Chats.selectAll().count() })
        assertEquals(beforeConversations, conversationCount(first, second))
        baselines.forEach { (uid, seq) -> assertEquals(seq, latestEventSeq(uid)) }

        assertIs<InjectedChatWriteRollbackException>(
            runCatching { failing.createGroup("must roll back", null, first, listOf(second)) }
                .exceptionOrNull(),
        )
        assertEquals(beforeChats, transaction { Chats.selectAll().count() })
        assertEquals(beforeConversations, conversationCount(first, second))
        assertTrue(transaction {
            GroupChats.selectAll().where { GroupChats.name eq "must roll back" }.empty()
        })
        baselines.forEach { (uid, seq) -> assertEquals(seq, latestEventSeq(uid)) }
    }

    @Test
    fun `metadata role mute and invite roll back with their event boundary`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("write-uow-owner"))
        val member = ctx.registerUser(uniqueUsername("write-uow-member"))
        val group = ctx.chatService.createGroup("before rollback", null, owner, listOf(member))
        val baselines = listOf(owner, member).associateWith(::latestEventSeq)
        val failing = ctx.freshChatService(failingUnitOfWork())

        assertFailsWith<InjectedChatWriteRollbackException> {
            failing.updateGroup(owner, group.chatId, name = "must not commit")
        }
        assertEquals("before rollback", groupName(group.chatId))

        assertFailsWith<InjectedChatWriteRollbackException> {
            failing.setRole(owner, group.chatId, member, 1)
        }
        assertEquals(0, memberRole(group.chatId, member))

        assertFailsWith<InjectedChatWriteRollbackException> {
            failing.transferOwner(owner, group.chatId, member)
        }
        assertEquals(2, memberRole(group.chatId, owner))
        assertEquals(0, memberRole(group.chatId, member))

        assertFailsWith<InjectedChatWriteRollbackException> {
            failing.muteAll(owner, group.chatId)
        }
        assertEquals(false, ctx.chatService.getChat(group.chatId)?.mutedAll)

        assertFailsWith<InjectedChatWriteRollbackException> {
            failing.muteMember(owner, group.chatId, member, 60)
        }
        assertTrue(transaction {
            GroupMemberMutes.selectAll().where {
                (GroupMemberMutes.chatId eq group.chatId) and (GroupMemberMutes.uid eq member)
            }.empty()
        })

        assertFailsWith<InjectedChatWriteRollbackException> {
            failing.createInviteLink(owner, group.chatId, "must not persist", 0, 0)
        }
        assertTrue(ctx.chatService.listInviteLinks(owner, group.chatId).isEmpty())

        val token = ctx.chatService.createInviteLink(owner, group.chatId, "revoke rollback", 0, 0)
        assertFailsWith<InjectedChatWriteRollbackException> {
            failing.revokeInviteLink(owner, token)
        }
        assertEquals(0L, ctx.chatService.getInviteInfo(token).revokedAt)
        baselines.forEach { (uid, seq) -> assertEquals(seq, latestEventSeq(uid)) }
    }

    @Test
    fun `committed owner revocation wins over concurrent stale update authorization`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("write-revoke-owner"))
        val member = ctx.registerUser(uniqueUsername("write-revoke-member"))
        val group = ctx.chatService.createGroup("revocation wins", null, owner, listOf(member))
        val baselines = listOf(owner, member).associateWith(::latestEventSeq)
        val revocationLocked = CompletableDeferred<Unit>()
        val releaseRevocation = CompletableDeferred<Unit>()
        val beforeChatLock = CompletableDeferred<Unit>()
        val concurrentService = ctx.freshChatService(
            unitOfWork = ctx.pgUnitOfWork,
            chatRepository = ExposedChatRepository(
                hooks = ChatRepositoryHooks { stage, hookChatId ->
                    if (stage == ChatRepositoryStage.BEFORE_CHAT_LOCK && hookChatId == group.chatId) {
                        beforeChatLock.complete(Unit)
                    }
                },
            ),
        )

        val revocation = async(Dispatchers.IO) {
            newSuspendedTransaction(Dispatchers.IO) {
                Chats.selectAll().where { Chats.chatId eq group.chatId }.forUpdate().single()
                Users.selectAll().where { Users.uid eq owner }.forUpdate().single()
                GroupMembers.selectAll().where {
                    (GroupMembers.chatId eq group.chatId) and (GroupMembers.uid eq owner)
                }.forUpdate().single()
                GroupMembers.update({
                    (GroupMembers.chatId eq group.chatId) and (GroupMembers.uid eq owner)
                }) { it[GroupMembers.role] = 0 }
                revocationLocked.complete(Unit)
                releaseRevocation.await()
            }
        }
        revocationLocked.await()

        val update = async(Dispatchers.Default) {
            runCatching {
                concurrentService.updateGroup(owner, group.chatId, name = "unauthorized")
            }.exceptionOrNull()
        }
        withContext(Dispatchers.IO) {
            withTimeout(5_000) { beforeChatLock.await() }
        }
        assertFalse(update.isCompleted, "update must wait for the locked authoritative Chat row")
        releaseRevocation.complete(Unit)

        val failure = withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                revocation.await()
                update.await()
            }
        }
        assertIs<IllegalArgumentException>(failure)
        assertEquals("revocation wins", groupName(group.chatId))
        baselines.forEach { (uid, seq) -> assertEquals(seq, latestEventSeq(uid)) }
    }

    @Test
    fun `fresh chat commands reject missing target users without ghost projections`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("write-missing-owner"))
        val beforeChats = transaction { Chats.selectAll().count() }

        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.createGroup("ghost", null, owner, listOf("missing-user"))
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.createPersonalChat(owner, "missing-user")
        }

        assertEquals(beforeChats, transaction { Chats.selectAll().count() })
        assertTrue(transaction {
            GroupMembers.selectAll().where { GroupMembers.uid eq "missing-user" }.empty()
        })
        assertTrue(transaction {
            Conversations.selectAll().where { Conversations.uid eq "missing-user" }.empty()
        })
    }

    private fun failingUnitOfWork() = ExposedPgUnitOfWork(
        onEventsCommitted = {},
        hooks = PgUnitOfWorkHooks { stage ->
            if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                throw InjectedChatWriteRollback
            }
        },
    )

    private fun latestEventSeq(uid: String): Long = transaction {
        SyncEvents.selectAll().where { SyncEvents.uid eq uid }
            .orderBy(SyncEvents.streamSeq, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(SyncEvents.streamSeq)
            ?: 0L
    }

    private fun conversationCount(vararg uids: String): Long = transaction {
        Conversations.selectAll().where { Conversations.uid inList uids.toList() }.count()
    }

    private fun groupName(chatId: String): String = transaction {
        GroupChats.selectAll().where { GroupChats.chatId eq chatId }.single()[GroupChats.name]
    }

    private fun memberRole(chatId: String, uid: String): Int = transaction {
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
        }.single()[GroupMembers.role]
    }

    private object InjectedChatWriteRollback : InjectedChatWriteRollbackException()
    private open class InjectedChatWriteRollbackException : RuntimeException("injected chat write rollback")
}
