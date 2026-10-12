package com.virjar.tk.server.integration

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.ScheduledMessageDraft
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.Test as KTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.virjar.tk.protocol.model.ScheduledMessageStatus

/** 定时发送（MSG-01/F3）：调度幂等、到点投递走完整发送管道、取消与失败结算、崩溃恢复幂等。 */
class ScheduledMessageIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `scheduled message is delivered exactly once through the normal send pipeline`() = runTest {
        val sender = ctx.registerUser()
        val peer = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(sender, peer)
        val service = ctx.scheduledMessageService

        val scheduled = service.schedule(
            sender,
            ScheduledMessageDraft(
                chatId = chat.chatId,
                messageType = MessageType.RICH_TEXT.code,
                deliverAt = System.currentTimeMillis() + 200,
                operationId = UUID.randomUUID().toString(),
                body = buildRichTextBody("定时到达的消息"),
            ),
        )
        assertEquals(ScheduledMessageStatus.WAITING, scheduled.status)
        assertEquals(1, service.listWaiting(sender, chat.chatId).size)

        assertEquals(0, service.deliverDue(System.currentTimeMillis()))
        assertTrue(ctx.messageService.getHistory(sender, chat.chatId, 0, 10).isEmpty())

        Thread.sleep(250)
        assertEquals(1, service.deliverDue(System.currentTimeMillis()))
        val history = ctx.messageService.getHistory(peer, chat.chatId, 0, 10)
        assertEquals(1, history.size)
        assertEquals(sender, history.single().senderUid)
        assertTrue(service.listWaiting(sender, chat.chatId).isEmpty())
        assertEquals(0, service.deliverDue(System.currentTimeMillis()))
    }

    @Test
    fun `same operation id returns the original schedule without creating a second one`() = runTest {
        val sender = ctx.registerUser()
        val peer = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(sender, peer)
        val service = ctx.scheduledMessageService

        val draft = ScheduledMessageDraft(
            chatId = chat.chatId,
            messageType = MessageType.RICH_TEXT.code,
            deliverAt = System.currentTimeMillis() + 60_000,
            operationId = "same-op",
            body = buildRichTextBody("first"),
        )
        val first = service.schedule(sender, draft)
        val replay = service.schedule(sender, draft.copy(body = buildRichTextBody("replayed body")))
        assertEquals(first.scheduledId, replay.scheduledId)
        assertEquals(1, service.listWaiting(sender, chat.chatId).size)
    }

    @Test
    fun `cancel prevents delivery and only the owner can cancel`() = runTest {
        val owner = ctx.registerUser()
        val peer = ctx.registerUser()
        val outsider = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(owner, peer)
        val service = ctx.scheduledMessageService
        val operationId = UUID.randomUUID().toString()

        val scheduled = service.schedule(
            owner,
            ScheduledMessageDraft(
                chatId = chat.chatId,
                messageType = MessageType.RICH_TEXT.code,
                deliverAt = System.currentTimeMillis() + 60_000,
                operationId = operationId,
                body = buildRichTextBody("永远不会发出的消息"),
            ),
        )
        assertFalse(service.cancel(outsider, scheduled.scheduledId, operationId))
        assertFalse(service.cancel(owner, scheduled.scheduledId, "wrong-op"))
        assertTrue(service.cancel(owner, scheduled.scheduledId, operationId))
        assertTrue(service.cancel(owner, scheduled.scheduledId, operationId))
        service.deliverDue(System.currentTimeMillis() + 120_000)
        assertTrue(ctx.messageService.getHistory(owner, chat.chatId, 0, 10).isEmpty())
    }

    @Test
    fun `delivery failure settles as failed and stays in the owner list`() = runTest {
        val owner = ctx.registerUser()
        val peer = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(owner, peer)
        val service = ctx.scheduledMessageService

        service.schedule(
            owner,
            ScheduledMessageDraft(
                chatId = chat.chatId,
                messageType = MessageType.RICH_TEXT.code,
                deliverAt = System.currentTimeMillis() + 200,
                operationId = UUID.randomUUID().toString(),
                body = buildRichTextBody("将被权限漂移拦下的消息"),
            ),
        )
        ctx.contactService(peer).blacklist(owner)

        Thread.sleep(250)
        service.deliverDue(System.currentTimeMillis())
        val waiting = service.listWaiting(owner, chat.chatId)
        assertEquals(1, waiting.size)
        assertEquals(ScheduledMessageStatus.FAILED, waiting.single().status)
    }

    @Test
    fun `crash between delivery and settlement replays idempotently without duplicating`() = runTest {
        val sender = ctx.registerUser()
        val peer = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(sender, peer)
        val service = ctx.scheduledMessageService
        val scheduled = service.schedule(
            sender,
            ScheduledMessageDraft(
                chatId = chat.chatId,
                messageType = MessageType.RICH_TEXT.code,
                deliverAt = System.currentTimeMillis() + 100,
                operationId = UUID.randomUUID().toString(),
                body = buildRichTextBody("崩溃恢复场景"),
            ),
        )
        Thread.sleep(150)
        service.deliverDue(System.currentTimeMillis())
        assertEquals(1, ctx.messageService.getHistory(sender, chat.chatId, 0, 10).size)

        // 模拟"投递完成但结算前崩溃"：调度行重置回 WAITING；重扫时 sendMessage 命中
        // 同一 clientMsgId 幂等索引，不产生第二条消息。
        ctx.scheduledMessageRepository.let { repo ->
            ctx.unitOfWork.write {
                repo.settle(transaction, scheduled.scheduledId, 3, 0, System.currentTimeMillis())
            }
        }
        service.deliverDue(System.currentTimeMillis())
        assertEquals(1, ctx.messageService.getHistory(sender, chat.chatId, 0, 10).size)
        assertTrue(service.listWaiting(sender, chat.chatId).isEmpty())
    }
}
