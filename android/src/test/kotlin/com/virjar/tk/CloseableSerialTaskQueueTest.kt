package com.virjar.tk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CloseableSerialTaskQueueTest {
    @Test
    fun `lifecycle captures and enqueues final write before scheduling barrier`() {
        val events = mutableListOf<String>()

        val captured = captureThenScheduleDocumentDraftFlush(
            captureDrafts = {
                events += "capture"
                events += "write-enqueued"
                true
            },
            scheduleFlush = { events += "barrier-enqueued" },
        )

        assertTrue(captured)
        assertEquals(listOf("capture", "write-enqueued", "barrier-enqueued"), events)
    }

    @Test
    fun `barrier waits for the last task without blocking its caller`() {
        val queue = CloseableSerialTaskQueue("draft-queue-barrier-test")
        val taskStarted = CountDownLatch(1)
        val allowTaskToFinish = CountDownLatch(1)
        try {
            assertTrue(
                queue.execute {
                    taskStarted.countDown()
                    allowTaskToFinish.await()
                },
            )
            assertTrue(taskStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val barrier = queue.barrier()
            assertFalse(barrier.isDone, "生命周期线程只拿到 future，不能同步等待正在执行的写入")

            allowTaskToFinish.countDown()
            assertTrue(barrier.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            allowTaskToFinish.countDown()
            queue.closeAsync().get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `close is idempotent drains accepted work and rejects later work`() {
        val queue = CloseableSerialTaskQueue("draft-queue-close-test")
        val taskStarted = CountDownLatch(1)
        val allowTaskToFinish = CountDownLatch(1)
        var lastBeatPersisted = false
        try {
            assertTrue(
                queue.execute {
                    taskStarted.countDown()
                    allowTaskToFinish.await()
                    lastBeatPersisted = true
                },
            )
            assertTrue(taskStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val firstClose = queue.closeAsync()
            val secondClose = queue.closeAsync()
            assertSame(firstClose, secondClose)
            assertFalse(queue.execute { error("must not run") })
            assertFalse(firstClose.isDone)

            allowTaskToFinish.countDown()
            assertTrue(firstClose.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(lastBeatPersisted)
        } finally {
            allowTaskToFinish.countDown()
            queue.closeAsync().get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 3L
    }
}
