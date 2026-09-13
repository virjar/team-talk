package com.virjar.tk.server.integration

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.user.SystemAccountUids
import com.virjar.tk.server.runtime.SystemCommandRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.io.IOException
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

    private suspend fun sendToService(
        senderUid: String,
        chatId: String,
        text: String,
        clientMsgId: String = UUID.randomUUID().toString(),
    ): Long =
        ctx.messageService.sendMessage(
            senderUid,
            Message(
                chatId = chatId,
                clientMsgId = clientMsgId,
                senderUid = senderUid,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                body = buildRichTextBody(text),
            ),
        )

    private suspend fun awaitServiceReplies(uid: String, minCount: Int): List<Message> = withContext(Dispatchers.IO) {
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
                return@withContext replies
            }
        }
        error("Service replies did not reach $minCount before the deadline")
    }

    @Test
    fun `help command triggers service reply`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-alice"))
        val chat = systemChatFor(alice)

        val originalId = UUID.randomUUID().toString()
        val originalSeq = sendToService(alice, chat.chatId, "/help", originalId)
        val replies = awaitServiceReplies(alice, 1)
        assertEquals(1, replies.size)
        assertTrue((replies.single().body as RichTextBody).plainText.contains("/help"), "回复应包含指令帮助文本")
        assertEquals("svc-$originalId", replies.single().clientMsgId, "短ID沿用既有回复身份")
        assertEquals(originalSeq, sendToService(alice, chat.chatId, "/help", originalId))
    }

    @Test
    fun `unknown command falls back to help text`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-alice2"))
        val chat = systemChatFor(alice)

        sendToService(alice, chat.chatId, "/不存在指令")
        val replies = awaitServiceReplies(alice, 1)
        assertEquals(1, replies.size)
        assertTrue((replies.single().body as RichTextBody).plainText.contains("未识别指令"))
    }

    @Test
    fun `maximum length command ids with the same prefix receive distinct replies`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-long"))
        val chat = systemChatFor(alice)
        val commonPrefix = "a".repeat(252)
        sendToService(alice, chat.chatId, "/help", commonPrefix + "0001")
        sendToService(alice, chat.chatId, "/help", commonPrefix + "0002")

        val replies = awaitServiceReplies(alice, 2)
        assertEquals(2, replies.size)
        assertEquals(2, replies.map { it.clientMsgId }.distinct().size)
        assertTrue(replies.all { it.clientMsgId.length <= 256 })
    }

    @Test
    fun `reply failure does not change committed command acknowledgement or replay`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-failure"))
        val chat = systemChatFor(alice)
        val attempted = CompletableDeferred<Unit>()
        SystemCommandRouter(sendServiceReply = { _, _, _ ->
            attempted.complete(Unit)
            throw IOException("injected reply failure")
        }).use { router ->
            val service = ctx.freshMessageService(systemCommandHandler = router)
            val command = Message(
                chatId = chat.chatId,
                clientMsgId = UUID.randomUUID().toString(),
                senderUid = alice,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                body = buildRichTextBody("/help"),
            )
            val first = service.sendMessage(alice, command)
            attempted.await()
            assertEquals(first, service.sendMessage(alice, command))
            val history = service.getHistory(alice, chat.chatId, 0L, 10)
            assertEquals(listOf(command.clientMsgId), history.map { it.clientMsgId })
        }
    }
}
