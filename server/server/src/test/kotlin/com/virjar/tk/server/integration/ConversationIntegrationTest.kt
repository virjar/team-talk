package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.ConversationUsages
import com.virjar.tk.server.infra.db.repository.ConversationReadQueryObserver
import com.virjar.tk.server.infra.db.repository.ConversationReadQueryStage
import com.virjar.tk.server.infra.db.repository.ExposedConversationRepository
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import com.virjar.tk.protocol.model.ConversationPage
import com.virjar.tk.protocol.model.ConversationPageRequest
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `list conversations is empty initially`() = runTest {
        val uid = ctx.registerUser()
        val conversations = ctx.conversationService.listConversations(uid)
        assertTrue(conversations.isEmpty())
    }

    @Test
    fun `conversation keyset pages are complete and display projection query count is constant`() = runTest {
        val owner = ctx.registerUser()
        val expectedChatIds = buildSet {
            repeat(ConversationPage.MAX_PAGE_SIZE + 1) {
                val peer = ctx.registerUser()
                add(ctx.chatService.createPersonalChat(owner, peer).chatId)
            }
        }
        val stages = mutableListOf<ConversationReadQueryStage>()
        val repository = ExposedConversationRepository(
            database = ctx.database,
            readQueryObserver = ConversationReadQueryObserver { stage -> stages += stage },
        )

        val first = repository.listConversationPage(
            uid = owner,
            afterChatId = null,
            pageSize = ConversationPage.MAX_PAGE_SIZE,
        )

        assertEquals(ConversationPage.MAX_PAGE_SIZE, first.items.size)
        assertNotNull(first.nextChatId)
        assertEquals(
            listOf(
                ConversationReadQueryStage.PAGE,
                ConversationReadQueryStage.PERSONAL_PEER,
                ConversationReadQueryStage.PEER_USER,
            ),
            stages,
            "page projection query count must not grow with page cardinality",
        )

        stages.clear()
        val second = repository.listConversationPage(
            uid = owner,
            afterChatId = first.nextChatId,
            pageSize = ConversationPage.MAX_PAGE_SIZE,
        )
        assertEquals(1, second.items.size)
        assertEquals(null, second.nextChatId)
        assertEquals(expectedChatIds, (first.items + second.items).mapTo(hashSetOf()) { it.chatId })
        assertEquals(
            listOf(
                ConversationReadQueryStage.PAGE,
                ConversationReadQueryStage.PERSONAL_PEER,
                ConversationReadQueryStage.PEER_USER,
            ),
            stages,
        )
    }

    @Test
    fun `service rejects malformed opaque conversation cursor`() = runTest {
        val uid = ctx.registerUser()

        assertFailsWith<IllegalArgumentException> {
            ctx.conversationService.listConversationPage(uid, ConversationPageRequest("not-a-real-cursor"))
        }
    }

    @Test
    fun `updating a later conversation cannot move it across the immutable page cursor`() = runTest {
        val owner = ctx.registerUser()
        val expectedChatIds = buildSet {
            repeat(ConversationPage.MAX_PAGE_SIZE + 1) {
                add(ctx.chatService.createPersonalChat(owner, ctx.registerUser()).chatId)
            }
        }
        val first = ctx.conversationService.listConversationPage(owner, ConversationPageRequest())
        val laterChatId = (expectedChatIds - first.items.mapTo(hashSetOf()) { it.chatId }).single()

        ctx.conversationService.setDraft(owner, laterChatId, "updated between pages")
        val second = ctx.conversationService.listConversationPage(
            owner,
            ConversationPageRequest(requireNotNull(first.nextCursor)),
        )

        assertEquals(expectedChatIds, (first.items + second.items).mapTo(hashSetOf()) { it.chatId })
        assertEquals("updated between pages", second.items.single { it.chatId == laterChatId }.draft)
    }

    @Test
    fun `conversation created after message`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendMessage(uid1, chat.chatId, "Hello")
        val conversations = ctx.conversationService.listConversations(uid1)
        assertTrue(conversations.any { it.chatId == chat.chatId })
    }

    @Test
    fun `sender does not receive own unread badge`() = runTest {
        val sender = ctx.registerUser()
        val recipient = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(sender, recipient)
        val seq = sendMessage(sender, chat.chatId, "Hello unread")

        val senderConv = ctx.conversationService.listConversations(sender).first { it.chatId == chat.chatId }
        val recipientConv = ctx.conversationService.listConversations(recipient).first { it.chatId == chat.chatId }
        assertEquals(seq, senderConv.readSeq)
        assertEquals(0, senderConv.unreadCount)
        assertEquals(1, recipientConv.unreadCount)
    }

    @Test
    fun `edit and revoke latest message refresh conversation preview`() = runTest {
        val sender = ctx.registerUser()
        val recipient = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(sender, recipient)
        val seq = sendMessage(sender, chat.chatId, "Before edit")
        val edited = com.virjar.tk.protocol.model.Message(
            chatId = chat.chatId,
            clientMsgId = java.util.UUID.randomUUID().toString(),
            serverSeq = seq,
            senderUid = sender,
            messageType = com.virjar.tk.protocol.MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = com.virjar.tk.protocol.body.buildRichTextBody("After edit"),
        )

        ctx.messageService.editMessage(sender, chat.chatId, seq, edited)
        val editedConv = ctx.conversationService.listConversations(recipient).first { it.chatId == chat.chatId }
        assertEquals("After edit", editedConv.lastMessage)
        assertEquals(com.virjar.tk.protocol.MessageType.RICH_TEXT.code, editedConv.lastMessageType)

        ctx.messageService.revokeMessage(sender, chat.chatId, seq)
        val revokedConv = ctx.conversationService.listConversations(recipient).first { it.chatId == chat.chatId }
        assertEquals(com.virjar.tk.protocol.MessageType.REVOKE.code, revokedConv.lastMessageType)
        assertEquals("", revokedConv.lastMessage)
    }

    @Test
    fun `set draft`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendMessage(uid1, chat.chatId, "Hi")
        ctx.conversationService.setDraft(uid1, chat.chatId, "Draft text")
        val conversations = ctx.conversationService.listConversations(uid1)
        val conv = conversations.first { it.chatId == chat.chatId }
        assertEquals("Draft text", conv.draft)
    }

    @Test
    fun `markdown source draft is not truncated`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val markdown = "```kotlin\n" + "println(\"完整源码\")\n".repeat(80) + "```"

        ctx.conversationService.setDraft(uid1, chat.chatId, markdown)

        assertEquals(markdown, ctx.conversationService.listConversations(uid1).single().draft)
    }

    @Test
    fun `conversation count capacity rejects every new entry path but keeps idempotent retries`() = runTest {
        val saturated = ctx.registerUser()
        val peer = ctx.registerUser()
        val outsider = ctx.registerUser()
        val existing = ctx.chatService.createPersonalChat(saturated, peer)
        transaction(ctx.database) {
            ConversationUsages.update({ ConversationUsages.uid eq saturated }) {
                it[ConversationUsages.conversationCount] =
                    ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER
                it[ConversationUsages.updatedAt] = System.currentTimeMillis()
            }
        }

        assertEquals(existing.chatId, ctx.chatService.createPersonalChat(saturated, peer).chatId)
        val creationFailure = assertFailsWith<IllegalArgumentException> {
            ctx.chatService.createGroup("over capacity", null, saturated, listOf(outsider))
        }
        assertEquals(ConversationCapacityPolicy.CONVERSATION_LIMIT_REASON, creationFailure.message)

        val owner = ctx.registerUser()
        val group = ctx.chatService.createGroup("bounded membership", null, owner, emptyList())
        val addTarget = ctx.registerUser()
        val inviteTarget = ctx.registerUser()
        transaction(ctx.database) {
            listOf(addTarget, inviteTarget).forEach { uid ->
                ConversationUsages.insert {
                    it[ConversationUsages.uid] = uid
                    it[ConversationUsages.conversationCount] =
                        ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER
                    it[ConversationUsages.draftCharacters] = 0L
                    it[ConversationUsages.updatedAt] = System.currentTimeMillis()
                }
            }
        }
        assertEquals(
            ConversationCapacityPolicy.CONVERSATION_LIMIT_REASON,
            assertFailsWith<IllegalArgumentException> {
                ctx.chatService.addMembers(owner, group.chatId, listOf(addTarget))
            }.message,
        )
        val token = ctx.chatService.createInviteLink(owner, group.chatId, "bounded", 0, 0)
        assertEquals(
            ConversationCapacityPolicy.CONVERSATION_LIMIT_REASON,
            assertFailsWith<IllegalArgumentException> {
                ctx.chatService.joinByInvite(inviteTarget, token)
            }.message,
        )
        assertTrue(ctx.chatService.getMembers(group.chatId).none { it.uid in setOf(addTarget, inviteTarget) })
    }

    @Test
    fun `draft aggregate and projection deletion update the per-user ledger exactly`() = runTest {
        val owner = ctx.registerUser()
        val member = ctx.registerUser()
        val group = ctx.chatService.createGroup("usage ledger", null, owner, listOf(member))

        ctx.conversationService.setDraft(member, group.chatId, "first draft")
        ctx.conversationService.setDraft(member, group.chatId, "tiny")
        assertEquals(4L, conversationUsage(member).second)

        ctx.chatService.removeMember(owner, group.chatId, member)
        assertEquals(0 to 0L, conversationUsage(member))

        ctx.conversationService.setDraft(owner, group.chatId, "owner draft")
        ctx.chatService.dissolveGroup(owner, group.chatId)
        assertEquals(0 to 0L, conversationUsage(owner))
    }

    @Test
    fun `draft capacity and missing usage ledger fail closed without partial mutation`() = runTest {
        val owner = ctx.registerUser()
        val peer = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(owner, peer)
        transaction(ctx.database) {
            ConversationUsages.update({ ConversationUsages.uid eq owner }) {
                it[ConversationUsages.draftCharacters] =
                    ConversationCapacityPolicy.MAX_TOTAL_DRAFT_CHARACTERS_PER_USER - 2L
                it[ConversationUsages.updatedAt] = System.currentTimeMillis()
            }
        }
        assertEquals(
            ConversationCapacityPolicy.DRAFT_LIMIT_REASON,
            assertFailsWith<IllegalArgumentException> {
                ctx.conversationService.setDraft(owner, chat.chatId, "three")
            }.message,
        )
        assertEquals(null, ctx.conversationRepo.getConversation(owner, chat.chatId)?.draft)

        transaction(ctx.database) {
            ConversationUsages.deleteWhere { ConversationUsages.uid eq peer }
        }
        assertFailsWith<IllegalStateException> {
            ctx.conversationService.setDraft(peer, chat.chatId, "must not commit")
        }
        assertEquals(null, ctx.conversationRepo.getConversation(peer, chat.chatId)?.draft)
        assertEquals(0L, transaction(ctx.database) {
            ConversationUsages.selectAll().where { ConversationUsages.uid eq peer }.count()
        })
    }

    @Test
    fun `set pin`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendMessage(uid1, chat.chatId, "Hi")
        ctx.conversationService.setPin(uid1, chat.chatId, true)
        val conversations = ctx.conversationService.listConversations(uid1)
        val conv = conversations.first { it.chatId == chat.chatId }
        assertEquals(true, conv.isPinned)
    }

    @Test
    fun `set mute`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendMessage(uid1, chat.chatId, "Hi")
        ctx.conversationService.setMute(uid1, chat.chatId, true)
        val conversations = ctx.conversationService.listConversations(uid1)
        val conv = conversations.first { it.chatId == chat.chatId }
        assertEquals(true, conv.isMuted)
    }

    @Test
    fun `mark read`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendMessage(uid1, chat.chatId, "Hi")
        ctx.conversationService.markRead(uid1, chat.chatId, 1)
        val conversations = ctx.conversationService.listConversations(uid1)
        val conv = conversations.first { it.chatId == chat.chatId }
        assertTrue(conv.readSeq >= 1)
    }

    @Test
    fun `outsider cannot mutate another chats conversation projection`() = runTest {
        val owner = ctx.registerUser()
        val member = ctx.registerUser()
        val outsider = ctx.registerUser()
        val chat = ctx.chatService.createGroup("PrivateConversation", null, owner, listOf(member))

        assertFailsWith<IllegalArgumentException> {
            ctx.conversationService.setDraft(outsider, chat.chatId, "forged")
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.conversationService.setPin(outsider, chat.chatId, true)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.conversationService.setMute(outsider, chat.chatId, true)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.conversationService.markRead(outsider, chat.chatId, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.conversationService.deleteConversation(outsider, chat.chatId)
        }
        assertTrue(ctx.conversationService.listConversations(outsider).none { it.chatId == chat.chatId })
    }

    @Test
    fun `mark read rejects a cursor ahead of authoritative chat max seq`() = runTest {
        val sender = ctx.registerUser()
        val recipient = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(sender, recipient)
        val maxSeq = sendMessage(sender, chat.chatId, "bounded read cursor")

        assertFailsWith<IllegalArgumentException> {
            ctx.conversationService.markRead(recipient, chat.chatId, maxSeq + 1)
        }
        assertEquals(0, ctx.conversationService.listConversations(recipient).single().readSeq)
    }

    @Test
    fun `delete conversation`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendMessage(uid1, chat.chatId, "Hi")
        ctx.conversationService.setDraft(uid1, chat.chatId, "bye")
        ctx.conversationService.deleteConversation(uid1, chat.chatId)
        val conversations = ctx.conversationService.listConversations(uid1)
        assertTrue(conversations.none { it.chatId == chat.chatId })
        assertEquals(1 to 0L, conversationUsage(uid1))
        ctx.conversationService.deleteConversation(uid1, chat.chatId)
        assertEquals(1 to 0L, conversationUsage(uid1))

        // 隐藏必须保留活动成员槽位。即使处于合法的账号上限，一条真正更新的消息
        // 也会恢复这一行，而不会再预留一个槽位，也不会阻塞持久消息投影 outbox。
        transaction(ctx.database) {
            ConversationUsages.update({ ConversationUsages.uid eq uid1 }) {
                it[ConversationUsages.conversationCount] =
                    ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER
                it[ConversationUsages.updatedAt] = System.currentTimeMillis()
            }
        }
        sendMessage(uid2, chat.chatId, "restore hidden conversation")
        assertTrue(ctx.conversationService.listConversations(uid1).any { it.chatId == chat.chatId })
        assertEquals(
            ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER,
            conversationUsage(uid1).first,
        )
        assertTrue(ctx.messageStore.getPendingProjectionOperations().isEmpty())
        assertEquals("UP", ctx.healthChecker.check().components["message-projection"]?.status)
    }

    private fun conversationUsage(uid: String): Pair<Int, Long> = transaction(ctx.database) {
        val row = ConversationUsages.selectAll().where { ConversationUsages.uid eq uid }.single()
        row[ConversationUsages.conversationCount] to row[ConversationUsages.draftCharacters]
    }

    private suspend fun sendMessage(senderUid: String, chatId: String, text: String): Long {
        val msg = com.virjar.tk.protocol.model.Message(
            chatId = chatId,
            clientMsgId = java.util.UUID.randomUUID().toString(),
            senderUid = senderUid,
            messageType = com.virjar.tk.protocol.MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = com.virjar.tk.protocol.body.buildRichTextBody(text),
        )
        return ctx.messageService.sendMessage(senderUid, msg)
    }
}
