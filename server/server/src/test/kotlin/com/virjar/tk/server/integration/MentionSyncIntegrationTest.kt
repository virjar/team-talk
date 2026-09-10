package com.virjar.tk.server.integration

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.MentionSyncPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MentionSyncIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private fun mentionMessage(
        senderUid: String,
        chatId: String,
        mentionedUid: String,
        displayName: String,
    ): Message {
        val body: RichTextBody = buildRichTextBody("你好 @[${displayName}](mention://${mentionedUid}) 看一下")
        return Message(
            chatId = chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = senderUid,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = body,
        )
    }

    @Test
    fun `mention projection arms only the mentioned recipient and markRead clears it`() = runTest {
        val alice = ctx.registerUser(uniqueUsername("mention-alice"))
        val bob = ctx.registerUser(uniqueUsername("mention-bob"))
        val carol = ctx.registerUser(uniqueUsername("mention-carol"))
        val chat = ctx.chatService.createGroup(id(), "提及群", null, alice, listOf(bob, carol))

        ctx.messageService.sendMessage(
            alice,
            mentionMessage(alice, chat.chatId, mentionedUid = bob, displayName = "小B"),
        )
        ctx.messageProjector.recoverPendingProjections()

        fun mentionEvents(uid: String) = ctx.syncEventReader.getEventsAfter(uid, 0L, 1_000)
            .filter { it.notifyType == NotifyType.MENTION_SYNC.code }
            .map { com.virjar.tk.protocol.ProtoCodec.decode(MentionSyncPayload, requireNotNull(it.payload)) }

        // 仅被提及的 bob 收到置位；alice（发送者）与 carol 不收到。
        val bobEvents = mentionEvents(bob)
        assertEquals(1, bobEvents.size)
        assertEquals(chat.chatId, bobEvents.single().chatId)
        assertTrue(bobEvents.single().mentioned)
        assertTrue(mentionEvents(alice).isEmpty())
        assertTrue(mentionEvents(carol).isEmpty())

        // 已读：bob 的读水位推进驱动 MENTION_SYNC(false) 仅回给 bob。
        ctx.conversationService.markRead(bob, chat.chatId, 1L)
        val cleared = mentionEvents(bob)
        assertEquals(2, cleared.size)
        assertTrue(!cleared.last().mentioned)
        assertTrue(mentionEvents(alice).isEmpty())
    }

    private fun id() = UUID.randomUUID().toString()
}

private object ProtoCodecs {
    // 解码 helper 占位：真实解码在下方测试体内通过 ProtoCodec 完成。
    fun decodeMention(payload: ByteArray?): MentionSyncPayload {
        val reader = com.virjar.tk.protocol.MentionSyncPayload
        return com.virjar.tk.protocol.ProtoCodec.decode(reader, requireNotNull(payload))
    }
}
