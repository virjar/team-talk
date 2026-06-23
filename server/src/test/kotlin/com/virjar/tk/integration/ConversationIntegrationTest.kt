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
