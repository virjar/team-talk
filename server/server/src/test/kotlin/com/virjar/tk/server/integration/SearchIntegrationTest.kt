package com.virjar.tk.server.integration

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `blank chat id searches all member chats only`() = runTest {
        val uid = ctx.registerUser()
        val otherUid = ctx.registerUser()
        val chat1 = ctx.chatService.createGroup("GlobalSearch1", null, uid, listOf(uid))
        val chat2 = ctx.chatService.createGroup("GlobalSearch2", null, uid, listOf(uid))
        val foreignChat = ctx.chatService.createGroup("ForeignSearch", null, otherUid, listOf(otherUid))
        val keyword = "globalneedle${java.util.UUID.randomUUID().toString().replace("-", "")}"

        ctx.messageService.sendMessage(uid, makeMessage(chat1.chatId, "$keyword first"))
        ctx.messageService.sendMessage(uid, makeMessage(chat2.chatId, "$keyword second"))
        ctx.messageService.sendMessage(otherUid, makeMessage(foreignChat.chatId, "$keyword private"))
        ctx.searchIndex.commit()

        val results = ctx.messageService.searchMessages(uid, "", keyword, 10)
        assertEquals(setOf(chat1.chatId, chat2.chatId), results.map { it.chatId }.toSet())
    }

    @Test
    fun `search keeps text beyond conversation preview through edit and revoke`() = runTest {
        val uid = ctx.registerUser()
        val chat = ctx.chatService.createGroup("LongSearchProjection", null, uid, listOf(uid))
        val originalKeyword = uniqueKeyword("originaltail")
        val editedKeyword = uniqueKeyword("editedtail")
        val originalText = "x".repeat(450) + " $originalKeyword"

        val seq = ctx.messageService.sendMessage(uid, makeMessage(chat.chatId, originalText))
        ctx.searchIndex.commit()

        val createPreview = conversationPreview(uid, chat.chatId)
        assertEquals(400, createPreview?.length)
        assertFalse(createPreview.orEmpty().contains(originalKeyword))
        assertEquals(
            listOf(seq),
            ctx.messageService.searchMessages(uid, chat.chatId, originalKeyword, 10).map { it.serverSeq },
        )

        val editedText = "y".repeat(450) + " $editedKeyword"
        ctx.messageService.editMessage(uid, chat.chatId, seq, makeMessage(chat.chatId, editedText))
        ctx.searchIndex.commit()

        val editPreview = conversationPreview(uid, chat.chatId)
        assertEquals(400, editPreview?.length)
        assertFalse(editPreview.orEmpty().contains(editedKeyword))
        assertTrue(ctx.messageService.searchMessages(uid, chat.chatId, originalKeyword, 10).isEmpty())
        assertEquals(
            listOf(seq),
            ctx.messageService.searchMessages(uid, chat.chatId, editedKeyword, 10).map { it.serverSeq },
        )

        ctx.messageService.revokeMessage(uid, chat.chatId, seq)
        ctx.searchIndex.commit()

        assertEquals("", conversationPreview(uid, chat.chatId))
        assertTrue(ctx.messageService.searchMessages(uid, chat.chatId, editedKeyword, 10).isEmpty())
    }

    private fun conversationPreview(uid: String, chatId: String): String? = transaction(ctx.database) {
        Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }.single()[Conversations.lastMessage]
    }

    private fun uniqueKeyword(prefix: String): String =
        prefix + java.util.UUID.randomUUID().toString().replace("-", "")

    private fun makeMessage(chatId: String, text: String): Message {
        return Message(
            chatId = chatId,
            clientMsgId = java.util.UUID.randomUUID().toString(),
            serverSeq = 0,
            senderUid = "",
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            flags = 0,
            body = buildRichTextBody(text),
        )
    }
}
