package com.virjar.tk.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CacheUseGateTest {
    @Test
    fun `close waits admitted database work and rejects stale pager use`() {
        val gate = CacheUseGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeReturned = AtomicBoolean(false)

        val databaseWork = thread(start = true, name = "blocked-cache-use") {
            gate.use {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val close = thread(start = true, name = "cache-close") {
            closeAttempted.countDown()
            gate.close {}
            closeReturned.set(true)
            closed.countDown()
        }

        assertTrue(closeAttempted.await(5, TimeUnit.SECONDS))
        assertFalse(closed.await(50, TimeUnit.MILLISECONDS), "close returned while DB lease was blocked")
        release.countDown()
        databaseWork.join(5_000)
        close.join(5_000)
        assertTrue(closeReturned.get())
        assertFailsWith<IllegalStateException> { gate.use { error("must not run") } }
        assertFalse(gate.runIfOpen { error("late lease result must not run") })
    }

    @Test
    fun `reentrant close unwinds callback then releases driver exactly once`() {
        val gate = CacheUseGate()
        var driverReleases = 0
        var publishedAfterClose = false

        assertFailsWith<CacheUseGateReentrantCloseException> {
            gate.use {
                gate.close { driverReleases += 1 }
                publishedAfterClose = true
            }
        }

        assertFalse(publishedAfterClose)
        assertTrue(driverReleases == 1)
        assertFalse(gate.runIfOpen { error("closed cache cannot admit a late result") })
        assertFalse(gate.close { driverReleases += 1 })
        assertTrue(driverReleases == 1)
    }
}
