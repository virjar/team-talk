package com.virjar.tk.integration

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
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
        val edited = com.virjar.tk.model.Message(
            chatId = chat.chatId,
            clientMsgId = java.util.UUID.randomUUID().toString(),
            serverSeq = seq,
            senderUid = sender,
            messageType = com.virjar.tk.protocol.MessageType.TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = com.virjar.tk.body.TextBody("After edit"),
        )

        ctx.messageService.editMessage(sender, chat.chatId, seq, edited)
        val editedConv = ctx.conversationService.listConversations(recipient).first { it.chatId == chat.chatId }
        assertEquals("After edit", editedConv.lastMessage)
        assertEquals(com.virjar.tk.protocol.MessageType.TEXT.code, editedConv.lastMessageType)

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
    fun `delete conversation`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendMessage(uid1, chat.chatId, "Hi")
        ctx.conversationService.deleteConversation(uid1, chat.chatId)
        val conversations = ctx.conversationService.listConversations(uid1)
        assertTrue(conversations.none { it.chatId == chat.chatId })
    }

    @Test
    fun `sync conversations`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        sendMessage(uid1, chat.chatId, "Hi")
        val all = ctx.conversationService.syncConversations(uid1, 0)
        assertTrue(all.any { it.chatId == chat.chatId })
    }

    private suspend fun sendMessage(senderUid: String, chatId: String, text: String): Long {
        val msg = com.virjar.tk.model.Message(
            chatId = chatId,
            clientMsgId = java.util.UUID.randomUUID().toString(),
            senderUid = senderUid,
            messageType = 1, // TEXT
            timestamp = System.currentTimeMillis(),
            body = com.virjar.tk.body.TextBody(text),
        )
        return ctx.messageService.sendMessage(senderUid, msg)
    }
}
