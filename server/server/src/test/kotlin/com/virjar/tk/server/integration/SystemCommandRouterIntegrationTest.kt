package com.virjar.tk.server.integration

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.user.SystemAccountUids
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 内测反馈 T058 第二阶段：服务号 /help 指令路由与幂等回复。 */
class SystemCommandRouterIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private suspend fun systemChatFor(uid: String) =
        ctx.chatService.getOrCreateSystemChat(uid, SystemAccountUids.SERVICE)

    private suspend fun sendToService(senderUid: String, chatId: String, text: String): Long =
        ctx.messageService.sendMessage(
            senderUid,
            Message(
                chatId = chatId,
                clientMsgId = UUID.randomUUID().toString(),
                senderUid = senderUid,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                body = buildRichTextBody(text),
            ),
        )

    private suspend fun awaitServiceReply(uid: String, minCount: Int): List<String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
            val replies = ctx.syncEventReader.getEventsAfter(uid, 0L, 10_000)
                .filter { it.notifyType == NotifyType.MESSAGE_RECV.code }
                .map { event ->
                    com.virjar.tk.protocol.ProtoCodec.decode(
                        com.virjar.tk.protocol.model.Message, requireNotNull(event.payload),
                    )
                }
                .filter { it.senderUid == SystemAccountUids.SERVICE }
            if (replies.size >= minCount) {
                return replies.map { (it.body as RichTextBody).plainText }
            }
        }
        return emptyList()
    }

    @Test
    fun `help command triggers service reply`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-alice"))
        val chat = systemChatFor(alice)

        sendToService(alice, chat.chatId, "/help")
        val replies = awaitServiceReply(alice, 1)
        assertEquals(1, replies.size)
        assertTrue(replies.single().contains("/help"), "回复应包含指令帮助文本")
    }

    @Test
    fun `unknown command falls back to help text`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-alice2"))
        val chat = systemChatFor(alice)

        sendToService(alice, chat.chatId, "/不存在指令")
        val replies = awaitServiceReply(alice, 1)
        assertEquals(1, replies.size)
        assertTrue(replies.single().contains("未识别指令"))
    }
}
