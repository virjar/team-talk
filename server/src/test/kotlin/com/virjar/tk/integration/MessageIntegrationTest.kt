package com.virjar.tk.integration

import com.virjar.tk.body.RichTextBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessageIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private suspend fun sendText(senderUid: String, chatId: String, text: String): Long {
        val msg = Message(
            chatId = chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = senderUid,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = buildRichTextBody(text),
        )
        return ctx.messageService.sendMessage(senderUid, msg)
    }

    @Test
    fun `send message returns seq`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val seq = sendText(uid1, chat.chatId, "Hello")
        assertTrue(seq > 0)
    }

    @Test
    fun `get message history`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendText(uid1, chat.chatId, "Msg1")
        sendText(uid1, chat.chatId, "Msg2")
        sendText(uid1, chat.chatId, "Msg3")
        val history = ctx.messageService.getHistory(uid1, chat.chatId, 0, 10)
        assertEquals(3, history.size)
    }

    @Test
    fun `get history with pagination`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendText(uid1, chat.chatId, "Msg1")
        sendText(uid1, chat.chatId, "Msg2")
        sendText(uid1, chat.chatId, "Msg3")
        val page1 = ctx.messageService.getHistory(uid1, chat.chatId, 0, 2)
        assertEquals(2, page1.size)
        // fromSeq 包含该 seq 的消息本身，取前一页最后一条的 seq-1
        val lastSeq = page1.last().serverSeq - 1
        val page2 = ctx.messageService.getHistory(uid1, chat.chatId, lastSeq, 2)
        assertEquals(1, page2.size)
    }

    @Test
    fun `revoke message`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val seq = sendText(uid1, chat.chatId, "Secret")
        ctx.messageService.revokeMessage(uid1, chat.chatId, seq)
        val history = ctx.messageService.getHistory(uid1, chat.chatId, 0, 10)
        val revoked = history.first { it.serverSeq == seq }
        assertTrue(revoked.flags != 0) // 标记为已撤回
    }

    @Test
    fun `send message to group`() = runTest {
        val creator = ctx.registerUser()
        val member1 = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(member1))
        val seq = sendText(creator, group.chatId, "Group msg")
        assertTrue(seq > 0)
    }

    @Test
    fun `search messages by keyword`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendText(uid1, chat.chatId, "Hello world")
        sendText(uid1, chat.chatId, "Random text")
        sendText(uid1, chat.chatId, "Hello again")
        val results = ctx.messageService.searchMessages(uid1, chat.chatId, "Hello", 10)
        assertTrue(results.size >= 2)
    }

    @Test
    fun `forward message after restart preserves target sequence and conversation`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val uid3 = ctx.registerUser()
        val chat1 = ctx.chatService.createPersonalChat(uid1, uid2)
        val chat2 = ctx.chatService.createPersonalChat(uid1, uid3)
        val seq = sendText(uid1, chat1.chatId, "Forward me")
        sendText(uid1, chat2.chatId, "Target 1")
        sendText(uid3, chat2.chatId, "Target 2")
        sendText(uid1, chat2.chatId, "Target 3")

        // 新 ChatStore 没有任何会话/maxSeq 热缓存，等价于服务进程重启。
        val restartedService = ctx.freshMessageService()
        val forwarded = restartedService.forwardMessage(uid1, chat1.chatId, seq, chat2.chatId)
        assertNotNull(forwarded)
        assertEquals(chat2.chatId, forwarded.chatId)
        assertEquals(4, forwarded.serverSeq)
        assertEquals("Forward me", (forwarded.body as RichTextBody).markdown)

        val targetHistory = restartedService.getHistory(uid1, chat2.chatId, 0, 10)
        assertEquals(listOf(4L, 3L, 2L, 1L), targetHistory.map { it.serverSeq })
        assertEquals(forwarded.clientMsgId, targetHistory.first().clientMsgId)

        val senderConversation = ctx.conversationService.listConversations(uid1)
            .first { it.chatId == chat2.chatId }
        val recipientConversation = ctx.conversationService.listConversations(uid3)
            .first { it.chatId == chat2.chatId }
        assertEquals(4, senderConversation.lastSeq)
        assertEquals(4, senderConversation.readSeq)
        assertEquals(0, senderConversation.unreadCount)
        assertEquals("Forward me", senderConversation.lastMessage)
        assertEquals(4, recipientConversation.lastSeq)
        assertEquals(2, recipientConversation.unreadCount)
    }

    @Test
    fun `client message id dedup`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val clientMsgId = UUID.randomUUID().toString()
        val msg = Message(
            chatId = chat.chatId,
            clientMsgId = clientMsgId,
            senderUid = uid1,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = buildRichTextBody("Dedup"),
        )
        val seq1 = ctx.messageService.sendMessage(uid1, msg)
        val seq2 = ctx.messageService.sendMessage(uid1, msg)
        assertEquals(seq1, seq2)
    }

    @Test
    fun `server rebuilds rich text derived fields from markdown`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        val msg = Message(
            chatId = chat.chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = uid1,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = RichTextBody(
                markdown = "**真实正文**",
                mentions = emptyList(),
                plainText = "客户端伪造字段",
            ),
        )

        val seq = ctx.messageService.sendMessage(uid1, msg)
        val stored = ctx.messageService.getHistory(uid1, chat.chatId, 0, 10)
            .first { it.serverSeq == seq }
        val storedBody = stored.body as RichTextBody
        assertEquals("**真实正文**", storedBody.markdown)
        assertEquals("真实正文", storedBody.plainText)
        assertTrue(ctx.messageService.searchMessages(uid1, chat.chatId, "客户端伪造字段", 10).isEmpty())
        assertEquals(seq, ctx.messageService.searchMessages(uid1, chat.chatId, "真实正文", 10).single().serverSeq)
        assertEquals(
            "真实正文",
            ctx.conversationService.listConversations(uid2).first { it.chatId == chat.chatId }.lastMessage,
        )
    }
}
