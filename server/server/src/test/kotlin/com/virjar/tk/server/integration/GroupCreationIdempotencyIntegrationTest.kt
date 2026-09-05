package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.chat.GroupCreationConflictException
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.ConversationUsages
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.GroupCreationCommands
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.SyncEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class GroupCreationIdempotencyIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `exact normalized replay returns one group without repeating events or quota`() = kotlinx.coroutines.test.runTest {
        val creator = ctx.registerUser(uniqueUsername("group-command-owner"))
        val firstMember = ctx.registerUser(uniqueUsername("group-command-first"))
        val secondMember = ctx.registerUser(uniqueUsername("group-command-second"))
        val recipients = listOf(creator, firstMember, secondMember)
        val operationId = UUID.randomUUID().toString()

        val first = ctx.chatService.createGroup(
            operationId = operationId,
            name = "  幂等项目群  ",
            avatar = "  https://example.invalid/group.png  ",
            creatorUid = creator,
            memberUids = listOf(secondMember, firstMember, secondMember),
        )
        val eventsAfterFirst = syncEventCount(recipients)
        val second = ctx.chatService.createGroup(
            operationId = operationId,
            name = "幂等项目群",
            avatar = "https://example.invalid/group.png",
            creatorUid = creator,
            memberUids = listOf(firstMember, secondMember),
        )

        assertEquals(first.chatId, second.chatId)
        assertEquals("幂等项目群", first.name)
        assertEquals(eventsAfterFirst, syncEventCount(recipients))
        transaction(ctx.database) {
            assertEquals(1L, GroupCreationCommands.selectAll().where {
                (GroupCreationCommands.creatorUid eq creator) and
                    (GroupCreationCommands.operationId eq operationId)
            }.count())
            assertEquals(1L, Chats.selectAll().where { Chats.chatId eq first.chatId }.count())
            assertEquals(3L, GroupMembers.selectAll().where {
                (GroupMembers.chatId eq first.chatId) and (GroupMembers.status eq 1)
            }.count())
            assertEquals(3L, Conversations.selectAll().where {
                Conversations.chatId eq first.chatId
            }.count())
            recipients.forEach { uid ->
                val usage = ConversationUsages.selectAll().where { ConversationUsages.uid eq uid }.single()
                assertEquals(1, usage[ConversationUsages.conversationCount])
            }
        }
    }

    @Test
    fun `operation id reuse with different payload is a stable conflict and creates no facts`() =
        kotlinx.coroutines.test.runTest {
            val creator = ctx.registerUser(uniqueUsername("group-command-conflict-owner"))
            val member = ctx.registerUser(uniqueUsername("group-command-conflict-member"))
            val operationId = UUID.randomUUID().toString()
            val created = ctx.chatService.createGroup(
                operationId,
                "原始群",
                null,
                creator,
                listOf(member),
            )
            val eventsAfterCreate = syncEventCount(listOf(creator, member))

            assertFailsWith<GroupCreationConflictException> {
                ctx.chatService.createGroup(
                    operationId,
                    "不同群名",
                    null,
                    creator,
                    listOf(member),
                )
            }

            assertEquals(eventsAfterCreate, syncEventCount(listOf(creator, member)))
            transaction(ctx.database) {
                assertEquals(1L, GroupCreationCommands.selectAll().where {
                    (GroupCreationCommands.creatorUid eq creator) and
                        (GroupCreationCommands.operationId eq operationId)
                }.count())
                assertEquals(1L, Chats.selectAll().where { Chats.chatId eq created.chatId }.count())
                assertEquals(2L, Conversations.selectAll().where {
                    Conversations.chatId eq created.chatId
                }.count())
            }
        }

    @Test
    fun `same operation id is independently scoped to each creator`() = kotlinx.coroutines.test.runTest {
        val firstCreator = ctx.registerUser(uniqueUsername("group-command-scope-first"))
        val secondCreator = ctx.registerUser(uniqueUsername("group-command-scope-second"))
        val operationId = UUID.randomUUID().toString()

        val first = ctx.chatService.createGroup(operationId, "第一群", null, firstCreator, emptyList())
        val second = ctx.chatService.createGroup(operationId, "第二群", null, secondCreator, emptyList())

        assertNotEquals(first.chatId, second.chatId)
        assertEquals(2L, transaction(ctx.database) {
            GroupCreationCommands.selectAll().where {
                GroupCreationCommands.operationId eq operationId
            }.count()
        })
    }

    @Test
    fun `concurrent first delivery of one operation returns one committed group to both callers`() =
        kotlinx.coroutines.test.runTest {
            val creator = ctx.registerUser(uniqueUsername("group-command-race-owner"))
            val member = ctx.registerUser(uniqueUsername("group-command-race-member"))
            val operationId = UUID.randomUUID().toString()
            val release = CompletableDeferred<Unit>()
            val ready = List(2) { CompletableDeferred<Unit>() }
            val eventsBefore = syncEventCount(listOf(creator, member))

            val attempts = ready.map { readiness ->
                async(Dispatchers.Default) {
                    readiness.complete(Unit)
                    release.await()
                    ctx.chatService.createGroup(
                        operationId = operationId,
                        name = "并发幂等群",
                        avatar = null,
                        creatorUid = creator,
                        memberUids = listOf(member),
                    )
                }
            }
            ready.forEach { it.await() }
            release.complete(Unit)
            val results = attempts.awaitAll()

            assertEquals(1, results.map { it.chatId }.distinct().size)
            assertEquals(eventsBefore + 2L, syncEventCount(listOf(creator, member)))
            transaction(ctx.database) {
                assertEquals(1L, GroupCreationCommands.selectAll().where {
                    (GroupCreationCommands.creatorUid eq creator) and
                        (GroupCreationCommands.operationId eq operationId)
                }.count())
                val chatId = results.first().chatId
                assertEquals(2L, GroupMembers.selectAll().where {
                    (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
                }.count())
                assertEquals(2L, Conversations.selectAll().where {
                    Conversations.chatId eq chatId
                }.count())
            }
        }

    private fun syncEventCount(uids: List<String>): Long = transaction(ctx.database) {
        SyncEvents.selectAll().where { SyncEvents.uid inList uids }.count()
    }
}
