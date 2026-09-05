package com.virjar.tk.server.infra.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HealthProbeTimeoutTest {
    @Test
    fun `deadline interrupts blocking worker and waits for cleanup`() {
        val never = CountDownLatch(1)
        val cleanedUp = CountDownLatch(1)

        lateinit var outcome: BlockingProbeOutcome<Unit>
        val elapsed = measureTimeMillis {
            outcome = runBlocking {
                runTimedBlockingHealthProbe(timeoutMillis = 200L) {
                    try {
                        never.await()
                    } finally {
                        cleanedUp.countDown()
                    }
                }
            }
        }

        assertIs<BlockingProbeOutcome.TimedOut>(outcome)
        assertTrue(cleanedUp.await(1, TimeUnit.SECONDS), "timed-out worker must release its resources")
        assertTrue(elapsed < 2_000L, "probe deadline must not wait for the blocking operation")
    }

    @Test
    fun `ordinary probe failure remains distinguishable from timeout`() = runBlocking {
        val expected = IllegalStateException("probe failure")

        val outcome = runTimedBlockingHealthProbe(timeoutMillis = 1_000L) {
            throw expected
        }

        val observed = assertIs<BlockingProbeOutcome.Failure>(outcome).cause
        // runInterruptible 会穿越协程栈恢复边界，可能复制普通异常。
        // 健康分类承诺的是类型/细节分离，而不是对象同一性。
        assertEquals(expected::class, observed::class)
        assertEquals(expected.message, observed.message)
    }

    @Test
    fun `caller cancellation is never converted into component failure`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val never = CountDownLatch(1)
        val cleanedUp = CountDownLatch(1)
        val probe = async {
            runTimedBlockingHealthProbe(timeoutMillis = 30_000L) {
                try {
                    started.complete(Unit)
                    never.await()
                } finally {
                    cleanedUp.countDown()
                }
            }
        }
        started.await()

        probe.cancel(CancellationException("caller stopped waiting"))

        assertFailsWith<CancellationException> { probe.await() }
        assertTrue(cleanedUp.await(1, TimeUnit.SECONDS), "cancelled worker must release its resources")
    }
}
