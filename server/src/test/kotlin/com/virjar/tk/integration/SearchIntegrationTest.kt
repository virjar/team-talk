package com.virjar.tk.integration

import com.virjar.tk.body.TextBody
import com.virjar.tk.model.Message
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `search messages by keyword`() = runTest {
        val uid = ctx.registerUser()
        val chat = ctx.chatService.createGroup("SearchGroup", null, uid, listOf(uid))

        // 发送消息
        val msg1 = makeMessage(chat.chatId, "hello world")
        val msg2 = makeMessage(chat.chatId, "random text")
        val msg3 = makeMessage(chat.chatId, "hello again")
        ctx.messageService.sendMessage(uid, msg1)
        ctx.messageService.sendMessage(uid, msg2)
        ctx.messageService.sendMessage(uid, msg3)

        // Lucene 需要提交
        ctx.searchIndex.commit()

        // 搜索 "hello"
        val results = ctx.messageService.searchMessages(uid, chat.chatId, "hello", 10)
        assertTrue(results.size >= 2, "应该找到至少2条包含'hello'的消息")
    }

    @Test
    fun `search returns empty for no match`() = runTest {
        val uid = ctx.registerUser()
        val chat = ctx.chatService.createGroup("SearchGroup2", null, uid, listOf(uid))

        val msg = makeMessage(chat.chatId, "你好世界")
        ctx.messageService.sendMessage(uid, msg)
        ctx.searchIndex.commit()

        val results = ctx.messageService.searchMessages(uid, chat.chatId, "不存在的关键词xyz", 10)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search denied for non-member`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createGroup("SearchGroup3", null, uid1, listOf(uid1))

        try {
            ctx.messageService.searchMessages(uid2, chat.chatId, "test", 10)
            throw AssertionError("应该抛出异常")
        } catch (e: IllegalArgumentException) {
            assertEquals("不是聊天成员", e.message)
        }
    }

    private fun makeMessage(chatId: String, text: String): Message {
        return Message(
            chatId = chatId,
            clientMsgId = java.util.UUID.randomUUID().toString(),
            serverSeq = 0,
            senderUid = "",
            messageType = 1, // TEXT
            timestamp = System.currentTimeMillis(),
            flags = 0,
            body = TextBody(text),
        )
    }
}
