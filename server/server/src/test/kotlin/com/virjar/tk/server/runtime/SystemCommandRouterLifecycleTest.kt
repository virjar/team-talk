package com.virjar.tk.server.runtime

import com.virjar.tk.server.domain.message.PendingServiceReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemCommandRouterLifecycleTest {
    private fun pendingOf(clientMsgId: String) = PendingServiceReply(
        chatId = "chat",
        clientMsgId = clientMsgId,
        replyClientMsgId = "svc-$clientMsgId",
        markdown = "reply",
    )

    @Test
    fun `server owner cancels and drains replies before dependent storage closes`() = runBlocking {
        val entered = CountDownLatch(1)
        val calls = AtomicInteger()
        val cancelled = AtomicBoolean()
        val storageClosed = AtomicBoolean()
        val router = SystemCommandRouter(
            sendServiceReply = { _, _, _ ->
                calls.incrementAndGet()
                entered.countDown()
                try {
                    awaitCancellation()
                } finally {
                    assertTrue(!storageClosed.get(), "回复必须在存储关闭前退出")
                    cancelled.set(true)
                }
            },
            settleServiceReply = { },
            pendingServiceReplies = { emptyList() },
        )
        val owner = ServerResourceOwner { _, _ -> }
        owner.own("message storage") {
            assertTrue(router.workersTerminated)
            storageClosed.set(true)
        }
        owner.ownDependencyBarrier("service replies", router, SystemCommandRouter::close,
            SystemCommandRouter::workersTerminated)
        try {
            router.dispatchServiceReply(pendingOf("command"))
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.IO) { owner.close() }
            assertTrue(cancelled.get())
            assertTrue(storageClosed.get())
            router.dispatchServiceReply(pendingOf("late-command"))
            assertEquals(1, calls.get(), "关闭后不得重新派发回复")
            owner.close()
        } finally {
            withContext(Dispatchers.IO) { owner.close() }
        }
    }

    @Test
    fun `successful send settles the record and admission rejection abandons it`() = runBlocking {
        val settled = mutableListOf<PendingServiceReply>()
        val sentReplyIds = mutableListOf<String>()
        val dispatched = CountDownLatch(2)
        val router = SystemCommandRouter(
            sendServiceReply = { _, replyId, _ ->
                dispatched.countDown()
                if (replyId == "svc-rejected") throw IllegalArgumentException("不是聊天成员")
                sentReplyIds += replyId
                1L
            },
            settleServiceReply = { settled += it },
            pendingServiceReplies = { emptyList() },
        )
        router.use {
            router.dispatchServiceReply(pendingOf("ok"))
            router.dispatchServiceReply(pendingOf("rejected"))
            assertTrue(withContext(Dispatchers.IO) { dispatched.await(5, TimeUnit.SECONDS) })
        }
        // 排空后：成功与终态拒绝都结算（记录删除），仅成功路径真正发送。
        assertEquals(listOf("svc-ok"), sentReplyIds)
        assertTrue(settled.map { it.clientMsgId }.containsAll(listOf("ok", "rejected")))
    }

    @Test
    fun `transient failure keeps the record and startup recovery advances each record once`() = runBlocking {
        val durable = mutableListOf(pendingOf("stuck-1"), pendingOf("stuck-2"))
        val attempts = AtomicInteger()
        val firstAttempt = CountDownLatch(1)
        val router = SystemCommandRouter(
            sendServiceReply = { _, _, _ ->
                if (attempts.incrementAndGet() == 1) firstAttempt.countDown()
                throw java.io.IOException("injected transient failure")
            },
            settleServiceReply = { durable.remove(it) },
            pendingServiceReplies = { limit -> durable.toList().take(limit) },
        )
        router.use {
            router.dispatchServiceReply(pendingOf("stuck-1"))
            assertTrue(withContext(Dispatchers.IO) { firstAttempt.await(5, TimeUnit.SECONDS) })
            val attempted = router.recoverPendingServiceReplies(pageSize = 100)
            // 暂态失败不结算：记录仍在，但恢复循环对每条记录只尝试一次，不无限重试。
            assertEquals(2, attempted)
            assertEquals(3, attempts.get())
            assertEquals(2, durable.size, "暂态失败记录必须保留给下一次启动")
        }
    }

    @Test
    fun `startup recovery settles records the send chain accepts`() = runBlocking {
        val durable = mutableListOf(pendingOf("a"), pendingOf("b"))
        val router = SystemCommandRouter(
            sendServiceReply = { _, _, _ -> 1L },
            settleServiceReply = { durable.remove(it) },
            pendingServiceReplies = { limit -> durable.toList().take(limit) },
        )
        router.use {
            assertEquals(2, router.recoverPendingServiceReplies())
        }
        assertTrue(durable.isEmpty())
    }
}
