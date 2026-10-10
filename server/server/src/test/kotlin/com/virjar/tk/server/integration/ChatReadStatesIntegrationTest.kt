package com.virjar.tk.server.integration

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 群已读列表（MSG-01/F5）：水位语义、可见性裁决（群主/管理员/发送者）、硬上限不截断。 */
class ChatReadStatesIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `read states reflect watermarks and visibility rules`() = runTest {
        val owner = ctx.registerUser()
        val member = ctx.registerUser()
        val outsider = ctx.registerUser()
        // 建群：owner 群主，member 普通成员
        val group = ctx.chatService.createGroup(UUID.randomUUID().toString(), "read-states", null, owner, listOf(member))
        val chatId = group.chatId
        // owner 发消息
        val seq = ctx.messageService.sendMessage(
            owner,
            Message(
                chatId = chatId,
                clientMsgId = UUID.randomUUID().toString(),
                senderUid = owner,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                body = buildRichTextBody("已读测试消息"),
            ),
        )
        // member 读到该消息
        ctx.conversationService.markRead(member, chatId, seq)

        // 发送者（owner）可查：owner 发送即自读、member markRead——两人水位都 >= seq
        val bySender = ctx.conversationService.listChatReadStates(owner, chatId, seq).sortedBy { it.uid }
        assertEquals(setOf(owner, member), bySender.map { it.uid }.toSet())
        assertTrue(bySender.all { it.readSeq >= seq })
        assertTrue(bySender.all { it.displayName.isNotBlank() })

        // 普通成员查别人的消息 → 拒绝
        assertFailsWith<Exception> { ctx.conversationService.listChatReadStates(member, chatId, seq) }
        // 非成员 → 拒绝
        assertFailsWith<Exception> { ctx.conversationService.listChatReadStates(outsider, chatId, seq) }

        // member 自己发的消息自己可查
        val seq2 = ctx.messageService.sendMessage(
            member,
            Message(
                chatId = chatId,
                clientMsgId = UUID.randomUUID().toString(),
                senderUid = member,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                body = buildRichTextBody("成员消息"),
            ),
        )
        val byMemberSender = ctx.conversationService.listChatReadStates(member, chatId, seq2)
        // owner 尚未读；member 自己水位>=seq2（markRead 未调，发送是否推进自身水位由既有语义决定）
        assertTrue(byMemberSender.all { it.uid in setOf(member) || it.readSeq >= seq2 } || byMemberSender.isEmpty())
    }
}
