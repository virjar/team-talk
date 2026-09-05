package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupCreationCommands
import com.virjar.tk.server.infra.db.GroupMemberMutes
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.InviteLinkCreationReceipts
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.repository.ChatRepositoryHooks
import com.virjar.tk.server.infra.db.repository.ChatRepositoryStage
import com.virjar.tk.server.infra.db.repository.ExposedChatRepository
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 针对其余 Chat 命令 + 持久事件提交边界的 PostgreSQL 覆盖测试。 */
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
        val beforeChats = transaction(ctx.database) { Chats.selectAll().count() }
        val beforeConversations = conversationCount(first, second)
        val baselines = listOf(first, second).associateWith(::latestEventSeq)
        val failing = ctx.freshChatService(failingUnitOfWork())

        assertIs<InjectedChatWriteRollbackException>(
            runCatching { failing.createPersonalChat(first, second) }.exceptionOrNull(),
        )
        assertEquals(beforeChats, transaction(ctx.database) { Chats.selectAll().count() })
        assertEquals(beforeConversations, conversationCount(first, second))
        baselines.forEach { (uid, seq) -> assertEquals(seq, latestEventSeq(uid)) }

        val groupOperationId = UUID.randomUUID().toString()
        assertIs<InjectedChatWriteRollbackException>(
            runCatching {
                failing.createGroup(groupOperationId, "must roll back", null, first, listOf(second))
            }
                .exceptionOrNull(),
        )
        assertEquals(beforeChats, transaction(ctx.database) { Chats.selectAll().count() })
        assertEquals(beforeConversations, conversationCount(first, second))
        assertTrue(transaction(ctx.database) {
            GroupChats.selectAll().where { GroupChats.name eq "must roll back" }.empty()
        })
        assertTrue(transaction(ctx.database) {
            GroupCreationCommands.selectAll().where {
                (GroupCreationCommands.creatorUid eq first) and
                    (GroupCreationCommands.operationId eq groupOperationId)
            }.empty()
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
        assertTrue(transaction(ctx.database) {
            GroupMemberMutes.selectAll().where {
                (GroupMemberMutes.chatId eq group.chatId) and (GroupMemberMutes.uid eq member)
            }.empty()
        })

        val inviteOperationId = UUID.randomUUID().toString()
        assertFailsWith<InjectedChatWriteRollbackException> {
            failing.createInviteLink(
                inviteOperationId,
                System.currentTimeMillis(),
                owner,
                group.chatId,
                "must not persist",
                0,
                0,
            )
        }
        assertTrue(ctx.chatService.listInviteLinks(owner, group.chatId).isEmpty())
        assertTrue(transaction(ctx.database) {
            InviteLinkCreationReceipts.selectAll().where {
                (InviteLinkCreationReceipts.actorUid eq owner) and
                    (InviteLinkCreationReceipts.operationId eq inviteOperationId)
            }.empty()
        })

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
                database = ctx.database,
                hooks = ChatRepositoryHooks { stage, hookChatId ->
                    if (stage == ChatRepositoryStage.BEFORE_CHAT_LOCK && hookChatId == group.chatId) {
                        beforeChatLock.complete(Unit)
                    }
                },
            ),
        )

        val revocation = async(Dispatchers.IO) {
            newSuspendedTransaction(context = Dispatchers.IO, db = ctx.database) {
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
        val beforeChats = transaction(ctx.database) { Chats.selectAll().count() }

        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.createGroup("ghost", null, owner, listOf("missing-user"))
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.createPersonalChat(owner, "missing-user")
        }

        assertEquals(beforeChats, transaction(ctx.database) { Chats.selectAll().count() })
        assertTrue(transaction(ctx.database) {
            GroupMembers.selectAll().where { GroupMembers.uid eq "missing-user" }.empty()
        })
        assertTrue(transaction(ctx.database) {
            Conversations.selectAll().where { Conversations.uid eq "missing-user" }.empty()
        })
    }

    private fun failingUnitOfWork() = ExposedPgUnitOfWork(
        database = ctx.database,
        onEventsCommitted = {},
        hooks = PgUnitOfWorkHooks { stage ->
            if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                throw InjectedChatWriteRollback
            }
        },
    )

    private fun latestEventSeq(uid: String): Long = transaction(ctx.database) {
        SyncEvents.selectAll().where { SyncEvents.uid eq uid }
            .orderBy(SyncEvents.streamSeq, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(SyncEvents.streamSeq)
            ?: 0L
    }

    private fun conversationCount(vararg uids: String): Long = transaction(ctx.database) {
        Conversations.selectAll().where { Conversations.uid inList uids.toList() }.count()
    }

    private fun groupName(chatId: String): String = transaction(ctx.database) {
        GroupChats.selectAll().where { GroupChats.chatId eq chatId }.single()[GroupChats.name]
    }

    private fun memberRole(chatId: String, uid: String): Int = transaction(ctx.database) {
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
        }.single()[GroupMembers.role]
    }

    private object InjectedChatWriteRollback : InjectedChatWriteRollbackException()
    private open class InjectedChatWriteRollbackException : RuntimeException("injected chat write rollback")
}
