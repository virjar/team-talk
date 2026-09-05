package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.Friends
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.protocol.model.ConversationPage
import com.virjar.tk.protocol.model.SyncCheckpointContactPage
import com.virjar.tk.protocol.model.SyncCheckpointPageRequest
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncCheckpointPaginationIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()

        private const val ACTIVE = 1
        private const val GROUP_CHAT_TYPE = 2
        private const val OWNER_ROLE = 2
        private const val CONTACT_COUNT = SyncCheckpointContactPage.MAX_PAGE_SIZE + 1
        private const val CONVERSATION_COUNT = ConversationPage.MAX_PAGE_SIZE + 1
    }

    private val ctx get() = ext.env

    @Test
    fun `257 contacts are returned as stable 256 plus 1 keyset pages`() = runTest {
        val ownerUid = ctx.registerUser(uniqueUsername("checkpoint-page-owner"))
        val friendUids = List(CONTACT_COUNT) { index ->
            "checkpoint-contact-${index.toString().padStart(3, '0')}"
        }
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            Users.batchInsert(friendUids, shouldReturnGeneratedValues = false) { friendUid ->
                this[Users.uid] = friendUid
                this[Users.username] = friendUid
                this[Users.name] = "Checkpoint ${friendUid.takeLast(3)}"
                this[Users.passwordHash] = "!checkpoint-fixture"
                this[Users.createdAt] = now
                this[Users.updatedAt] = now
            }
            Friends.batchInsert(friendUids, shouldReturnGeneratedValues = false) { friendUid ->
                this[Friends.uid] = ownerUid
                this[Friends.friendUid] = friendUid
                this[Friends.status] = ACTIVE
                this[Friends.createdAt] = now
            }
        }

        val sessionId = "checkpoint-contact-pagination"
        val header = ctx.syncCheckpointService.beginCheckpoint(
            uid = ownerUid,
            sessionId = sessionId,
            claimedDatasetId = ctx.syncEventReader.datasetId,
        )
        val firstPage = ctx.syncCheckpointService.listContacts(
            ownerUid,
            sessionId,
            SyncCheckpointPageRequest(header.checkpointId),
        )
        val firstCursor = assertNotNull(firstPage.nextCursor)
        val secondPage = ctx.syncCheckpointService.listContacts(
            ownerUid,
            sessionId,
            SyncCheckpointPageRequest(header.checkpointId, firstCursor),
        )

        assertEquals(SyncCheckpointContactPage.MAX_PAGE_SIZE, firstPage.items.size)
        assertEquals(friendUids.take(SyncCheckpointContactPage.MAX_PAGE_SIZE), firstPage.items.map { it.friendUid })
        assertEquals(friendUids[SyncCheckpointContactPage.MAX_PAGE_SIZE - 1], firstCursor)
        assertEquals(listOf(friendUids.last()), secondPage.items.map { it.friendUid })
        assertNull(secondPage.nextCursor)

        val returned = (firstPage.items + secondPage.items).map { it.friendUid }
        assertEquals(friendUids, returned)
        assertEquals(CONTACT_COUNT, returned.distinct().size)
    }

    @Test
    fun `17 conversations are returned as stable 16 plus 1 opaque keyset pages`() = runTest {
        val ownerUid = ctx.registerUser(uniqueUsername("checkpoint-conv-owner"))
        val chatIds = List(CONVERSATION_COUNT) { index ->
            "checkpoint-chat-${index.toString().padStart(2, '0')}"
        }
        val expectedOrder = chatIds.sortedDescending()
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            Chats.batchInsert(chatIds, shouldReturnGeneratedValues = false) { chatId ->
                this[Chats.chatId] = chatId
                this[Chats.chatType] = GROUP_CHAT_TYPE
                this[Chats.status] = ACTIVE
                this[Chats.createdAt] = now
                this[Chats.updatedAt] = now
            }
            GroupChats.batchInsert(chatIds, shouldReturnGeneratedValues = false) { chatId ->
                this[GroupChats.chatId] = chatId
                this[GroupChats.name] = "Checkpoint ${chatId.takeLast(2)}"
                this[GroupChats.creator] = ownerUid
                this[GroupChats.updatedAt] = now
            }
            GroupMembers.batchInsert(chatIds, shouldReturnGeneratedValues = false) { chatId ->
                this[GroupMembers.chatId] = chatId
                this[GroupMembers.chatType] = GROUP_CHAT_TYPE
                this[GroupMembers.uid] = ownerUid
                this[GroupMembers.role] = OWNER_ROLE
                this[GroupMembers.status] = ACTIVE
                this[GroupMembers.joinedAt] = now
            }
            Conversations.batchInsert(chatIds, shouldReturnGeneratedValues = false) { chatId ->
                this[Conversations.uid] = ownerUid
                this[Conversations.chatId] = chatId
                this[Conversations.chatType] = GROUP_CHAT_TYPE
                this[Conversations.updatedAt] = now
            }
        }

        val sessionId = "checkpoint-conversation-pagination"
        val header = ctx.syncCheckpointService.beginCheckpoint(
            uid = ownerUid,
            sessionId = sessionId,
            claimedDatasetId = ctx.syncEventReader.datasetId,
        )
        val firstPage = ctx.syncCheckpointService.listConversations(
            ownerUid,
            sessionId,
            SyncCheckpointPageRequest(header.checkpointId),
        )
        val firstCursor = assertNotNull(firstPage.nextCursor)
        val secondPage = ctx.syncCheckpointService.listConversations(
            ownerUid,
            sessionId,
            SyncCheckpointPageRequest(header.checkpointId, firstCursor),
        )

        assertEquals(ConversationPage.MAX_PAGE_SIZE, firstPage.items.size)
        assertEquals(expectedOrder.take(ConversationPage.MAX_PAGE_SIZE), firstPage.items.map { it.chatId })
        assertEquals(listOf(expectedOrder.last()), secondPage.items.map { it.chatId })
        assertNull(secondPage.nextCursor)

        val returned = (firstPage.items + secondPage.items).map { it.chatId }
        assertEquals(expectedOrder, returned)
        assertEquals(CONVERSATION_COUNT, returned.distinct().size)
    }
}
