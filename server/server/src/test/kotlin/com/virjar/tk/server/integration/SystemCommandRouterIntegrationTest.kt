package com.virjar.tk.server.integration

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.message.PendingServiceReply
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

/** 内测反馈 T058 第二阶段：服务号 /help 指令路由与幂等回复；CODE-01：回复的持久恢复。 */
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
    fun `reply failure keeps the durable record and leaves command acknowledgement unchanged`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-failure"))
        val chat = systemChatFor(alice)
        val attempted = CompletableDeferred<Unit>()
        val router = SystemCommandRouter(
            sendServiceReply = { _, _, _ ->
                attempted.complete(Unit)
                throw IOException("injected reply failure")
            },
            settleServiceReply = { pending ->
                ctx.messageStore.markServiceReplySettled(pending.chatId, pending.clientMsgId)
            },
            pendingServiceReplies = { limit -> ctx.messageStore.pendingServiceReplies(limit) },
        )
        router.use {
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
            // CODE-01：回复失败后记录保留，等待启动恢复而不是永久丢失。
            val pending = ctx.messageStore.findPendingServiceReply(chat.chatId, command.clientMsgId)
            assertEquals("svc-${command.clientMsgId}", pending?.replyClientMsgId)
        }
    }

    @Test
    fun `successful reply settles the durable record`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-settle"))
        val chat = systemChatFor(alice)

        val originalId = UUID.randomUUID().toString()
        sendToService(alice, chat.chatId, "/help", originalId)
        awaitServiceReplies(alice, 1)

        withContext(Dispatchers.IO) {
            kotlinx.coroutines.delay(200)
        }
        assertEquals(null, ctx.messageStore.findPendingServiceReply(chat.chatId, originalId))
        assertEquals(emptyList(), ctx.messageStore.pendingServiceReplies().map { it.clientMsgId })
    }

    @Test
    fun `startup recovery drains a stranded record exactly once`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-recover"))
        val chat = systemChatFor(alice)
        val attempted = CompletableDeferred<Unit>()

        // 第一阶段：回复派发中断（模拟进程死亡后仅剩持久记录），命令已提交。
        val failing = SystemCommandRouter(
            sendServiceReply = { _, _, _ ->
                attempted.complete(Unit)
                throw IOException("injected reply failure")
            },
            settleServiceReply = { pending ->
                ctx.messageStore.markServiceReplySettled(pending.chatId, pending.clientMsgId)
            },
            pendingServiceReplies = { limit -> ctx.messageStore.pendingServiceReplies(limit) },
        )
        val originalId = UUID.randomUUID().toString()
        failing.use { router ->
            val service = ctx.freshMessageService(systemCommandHandler = router)
            service.sendMessage(
                alice,
                Message(
                    chatId = chat.chatId,
                    clientMsgId = originalId,
                    senderUid = alice,
                    messageType = MessageType.RICH_TEXT.code,
                    timestamp = System.currentTimeMillis(),
                    body = buildRichTextBody("/help"),
                ),
            )
            attempted.await()
        }
        val stranded = ctx.messageStore.findPendingServiceReply(chat.chatId, originalId)
        checkNotNull(stranded)

        // 第二阶段：启动恢复——真实发送链接管，按冻结身份补发一次并结算。
        val recovering = SystemCommandRouter(
            sendServiceReply = { chatId, clientMsgId, markdown ->
                ctx.messageService.sendServiceReply(chatId, clientMsgId, markdown)
            },
            settleServiceReply = { pending ->
                ctx.messageStore.markServiceReplySettled(pending.chatId, pending.clientMsgId)
            },
            pendingServiceReplies = { limit -> ctx.messageStore.pendingServiceReplies(limit) },
        )
        recovering.use { router ->
            // 共享测试环境里可能还有其他用例滞留的记录；本用例只关心自己的记录被推进。
            assertTrue(router.recoverPendingServiceReplies() >= 1)
        }
        val replies = awaitServiceReplies(alice, 1)
        assertEquals(listOf("svc-$originalId"), replies.map { it.clientMsgId })
        assertEquals(null, ctx.messageStore.findPendingServiceReply(chat.chatId, originalId))

        // 恢复后的重放幂等：同命令再发送不产生新消息与新回复。
        val historyBefore = ctx.messageService.getHistory(alice, chat.chatId, 0L, 10).size
        val recoveringAgain = SystemCommandRouter(
            sendServiceReply = { chatId, clientMsgId, markdown ->
                ctx.messageService.sendServiceReply(chatId, clientMsgId, markdown)
            },
            settleServiceReply = { pending ->
                ctx.messageStore.markServiceReplySettled(pending.chatId, pending.clientMsgId)
            },
            pendingServiceReplies = { limit -> ctx.messageStore.pendingServiceReplies(limit) },
        )
        recoveringAgain.use { router ->
            router.recoverPendingServiceReplies()
        }
        assertEquals(null, ctx.messageStore.findPendingServiceReply(chat.chatId, originalId))
        assertEquals(historyBefore, ctx.messageService.getHistory(alice, chat.chatId, 0L, 10).size)
    }
}
