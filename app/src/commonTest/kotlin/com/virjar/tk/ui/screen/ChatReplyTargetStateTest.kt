package com.virjar.tk.ui.screen

import androidx.compose.runtime.saveable.SaverScope
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.body.ReplyBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ChatReplyTargetStateTest {

    @Test
    fun `回复目标保存为 clientMsgId 并可恢复`() {
        val original = SavedChatReplyTarget("reply-1")
        val saved = with(SavedChatReplyTargetSaver) { SaveEverythingScope.save(original) }

        assertEquals(listOf("reply-1"), saved)
        assertEquals(original, SavedChatReplyTargetSaver.restore(saved!!))
    }

    @Test
    fun `消息流晚到或刷新后按 clientMsgId 重新绑定`() {
        val savedTarget = SavedChatReplyTarget("reply-1")
        assertNull(savedTarget.bind(emptyList()))

        val first = message("reply-1", "旧对象")
        val refreshed = message("reply-1", "新对象")

        assertSame(first, savedTarget.bind(listOf(first)))
        assertSame(refreshed, savedTarget.bind(listOf(refreshed)))
    }

    @Test
    fun `只有服务端已确认消息能作为回复目标`() {
        assertNull(message("local-only", "发送中").confirmedReplyToMsgIdOrNull())
        assertEquals(
            "42",
            message("confirmed", "已确认").copy(serverSeq = 42L).confirmedReplyToMsgIdOrNull(),
        )
    }

    @Test
    fun `普通消息和回复消息发送前执行同一正文预算`() {
        val oversized = "a".repeat(MessageBodyPolicy.MAX_MARKDOWN_LENGTH + 1)
        val ordinary = message("ordinary", oversized)
        val reply = ordinary.copy(
            clientMsgId = "reply",
            messageType = MessageType.REPLY.code,
            body = ReplyBody(
                replyToMsgId = "42",
                replyToSenderUid = "user-2",
                content = oversized,
            ),
        )

        assertFailsWith<IllegalArgumentException> { canonicalizeChatMessageForSend(ordinary) }
        assertFailsWith<IllegalArgumentException> { canonicalizeChatMessageForSend(reply) }
    }

    private fun message(clientMsgId: String, content: String) = Message(
        chatId = "chat-1",
        clientMsgId = clientMsgId,
        senderUid = "user-1",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1L,
        body = buildRichTextBody(content),
    )

    private object SaveEverythingScope : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }
}
