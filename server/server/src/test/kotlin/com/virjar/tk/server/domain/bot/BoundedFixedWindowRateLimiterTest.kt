package com.virjar.tk.server.domain.bot

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedFixedWindowRateLimiterTest {
    @Test
    fun `window enforces limit and resets at expiry`() {
        var now = 1_000L
        val limiter = BoundedFixedWindowRateLimiter<String>(
            limit = 2,
            windowMillis = 100,
            maxTrackedKeys = 4,
            clock = { now },
        )

        assertTrue(limiter.tryAcquire("bot"))
        assertTrue(limiter.tryAcquire("bot"))
        assertFalse(limiter.tryAcquire("bot"))
        now += 100
        assertTrue(limiter.tryAcquire("bot"))
    }

    @Test
    fun `active key capacity fails closed and expired keys are reclaimed`() {
        var now = 5_000L
        val limiter = BoundedFixedWindowRateLimiter<String>(
            limit = 10,
            windowMillis = 100,
            maxTrackedKeys = 2,
            clock = { now },
        )

        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
        assertFalse(limiter.tryAcquire("c"))
        assertEquals(2, limiter.trackedKeyCount())

        now += 100
        assertTrue(limiter.tryAcquire("c"))
        assertEquals(1, limiter.trackedKeyCount())
    }

    @Test
    fun `concurrent requests cannot exceed limit`() {
        val limiter = BoundedFixedWindowRateLimiter<String>(
            limit = 20,
            windowMillis = 60_000,
            maxTrackedKeys = 4,
            clock = { 1_000L },
        )
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(100)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        try {
            repeat(100) {
                executor.execute {
                    start.await()
                    if (limiter.tryAcquire("bot")) accepted.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(20, accepted.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
