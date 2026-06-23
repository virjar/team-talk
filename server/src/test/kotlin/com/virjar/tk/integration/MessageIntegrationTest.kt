package com.virjar.tk.integration

import com.virjar.tk.body.TextBody
import com.virjar.tk.model.Message
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
            messageType = 1,
            timestamp = System.currentTimeMillis(),
            body = TextBody(text),
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
    fun `forward message`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val uid3 = ctx.registerUser()
        val chat1 = ctx.chatService.createPersonalChat(uid1, uid2)
        val chat2 = ctx.chatService.createPersonalChat(uid1, uid3)
        val seq = sendText(uid1, chat1.chatId, "Forward me")
        val forwarded = ctx.messageService.forwardMessage(uid1, chat1.chatId, seq, chat2.chatId)
        assertNotNull(forwarded)
        assertEquals(chat2.chatId, forwarded.chatId)
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
            messageType = 1,
            timestamp = System.currentTimeMillis(),
            body = TextBody("Dedup"),
        )
        val seq1 = ctx.messageService.sendMessage(uid1, msg)
        val seq2 = ctx.messageService.sendMessage(uid1, msg)
        assertEquals(seq1, seq2)
    }
}
