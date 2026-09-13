package com.virjar.tk.server.runtime

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
    @Test
    fun `server owner cancels and drains replies before dependent storage closes`() = runBlocking {
        val entered = CountDownLatch(1)
        val calls = AtomicInteger()
        val cancelled = AtomicBoolean()
        val storageClosed = AtomicBoolean()
        val router = SystemCommandRouter(sendServiceReply = { _, _, _ ->
            calls.incrementAndGet()
            entered.countDown()
            try {
                awaitCancellation()
            } finally {
                assertTrue(!storageClosed.get(), "回复必须在存储关闭前退出")
                cancelled.set(true)
            }
        })
        val owner = ServerResourceOwner { _, _ -> }
        owner.own("message storage") {
            assertTrue(router.workersTerminated)
            storageClosed.set(true)
        }
        owner.ownDependencyBarrier("service replies", router, SystemCommandRouter::close,
            SystemCommandRouter::workersTerminated)
        try {
            router.onServiceMessage("user", "chat", "command", "/help")
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.IO) { owner.close() }
            assertTrue(cancelled.get())
            assertTrue(storageClosed.get())
            router.onServiceMessage("user", "chat", "late-command", "/help")
            assertEquals(1, calls.get(), "关闭后不得重新派发回复")
            owner.close()
        } finally {
            withContext(Dispatchers.IO) { owner.close() }
        }
    }
}
