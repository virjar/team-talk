package com.virjar.tk.server.protocol.executor

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LooperTest {
    @Test
    fun `one hundred thousand excess commands do not grow the regular queue`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executed = AtomicInteger(0)
        val unexpected = AtomicInteger(0)
        val looper = Looper("looper-capacity-test", queueCapacity = 2).apply { start() }

        try {
            assertTrue(looper.post {
                entered.countDown()
                release.await()
                executed.incrementAndGet()
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(2) {
                assertTrue(looper.post { executed.incrementAndGet() })
            }

            val rejectedCommand: () -> Unit = { unexpected.incrementAndGet() }
            var rejected = 0
            repeat(100_000) {
                if (!looper.post(rejectedCommand)) rejected += 1
            }

            assertEquals(100_000, rejected)
            assertEquals(0, unexpected.get())
        } finally {
            release.countDown()
            looper.stop()
        }

        assertEquals(3, executed.get())
    }

    @Test
    fun `critical unregister sweep survives saturation and signals racing with a sweep`() {
        val markedForUnregister = ConcurrentHashMap.newKeySet<String>()
        val reaped = ConcurrentHashMap.newKeySet<String>()
        val firstSnapshotTaken = CountDownLatch(1)
        val releaseFirstSweep = CountDownLatch(1)
        val bothReaped = CountDownLatch(1)
        val normalCommands = CountDownLatch(2)
        val sweepCount = AtomicInteger(0)

        val looper = Looper(
            name = "looper-critical-unregister-test",
            queueCapacity = 2,
            criticalSweep = {
                val snapshot = markedForUnregister.toSet()
                if (sweepCount.incrementAndGet() == 1) {
                    firstSnapshotTaken.countDown()
                    releaseFirstSweep.await()
                }
                snapshot.forEach { session ->
                    if (markedForUnregister.remove(session)) reaped += session
                }
                if (reaped.containsAll(setOf("first", "racing"))) bothReaped.countDown()
            },
        ).apply { start() }

        try {
            markedForUnregister += "first"
            assertTrue(looper.signalCriticalSweep())
            assertTrue(firstSnapshotTaken.await(5, TimeUnit.SECONDS))

            // 关键回调正在运行。填满每个常规槽位，然后让另一个终态标记
            // 和一场断连风暴与第一个快照竞争。
            repeat(2) {
                assertTrue(looper.post { normalCommands.countDown() })
            }
            assertFalse(looper.post { error("regular reserve must be full") })
            markedForUnregister += "racing"
            repeat(100_000) {
                assertTrue(looper.signalCriticalSweep())
            }

            releaseFirstSweep.countDown()
            assertTrue(normalCommands.await(5, TimeUnit.SECONDS))
            assertTrue(bothReaped.await(5, TimeUnit.SECONDS))
            assertEquals(setOf("first", "racing"), reaped)
            assertTrue(sweepCount.get() >= 2)
        } finally {
            releaseFirstSweep.countDown()
            looper.stop()
        }
    }

    @Test
    fun `saturated suspend continuation is rejected and later admission still works`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedDone = CountDownLatch(1)
        val rejectedBlockRan = AtomicBoolean(false)
        val looper = Looper("looper-continuation-test", queueCapacity = 1).apply { start() }

        try {
            assertTrue(looper.post {
                entered.countDown()
                release.await()
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(looper.post { queuedDone.countDown() })

            assertFailsWith<RejectedExecutionException> {
                withTimeout(1_000) {
                    looper.suspendAwait {
                        rejectedBlockRan.set(true)
                    }
                }
            }
            assertFalse(rejectedBlockRan.get())

            release.countDown()
            assertTrue(queuedDone.await(5, TimeUnit.SECONDS))
            assertEquals(42, withTimeout(1_000) { looper.suspendAwait { 42 } })
        } finally {
            release.countDown()
            looper.stop()
        }
    }

    @Test
    fun `cancelled queued continuation releases capacity without running its block`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacementRan = CountDownLatch(1)
        val cancelledBlockRan = AtomicBoolean(false)
        val looper = Looper("looper-cancellation-test", queueCapacity = 1).apply { start() }

        try {
            assertTrue(looper.post {
                entered.countDown()
                release.await()
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                looper.suspendAwait { cancelledBlockRan.set(true) }
            }
            waiting.cancelAndJoin()

            assertTrue(looper.post { replacementRan.countDown() })
            release.countDown()
            assertTrue(replacementRan.await(5, TimeUnit.SECONDS))
            assertFalse(cancelledBlockRan.get())
        } finally {
            release.countDown()
            looper.stop()
        }
    }

    @Test
    fun `stop drains admitted work and runs finalizer on looper thread`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val stopFailure = AtomicReference<Throwable?>(null)
        val looper = Looper("looper-stop-drain-test", queueCapacity = 1).apply { start() }

        assertTrue(looper.post {
            entered.countDown()
            release.await()
            order += "running"
        })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(looper.post { order += "queued" })
        val stopper = Thread({
            try {
                looper.stop {
                    looper.checkLooper()
                    order += "finalizer"
                }
            } catch (error: Throwable) {
                stopFailure.set(error)
            }
        }, "looper-test-stopper").apply { start() }

        try {
            // stop 已停止准入并在等待终结；此时再放行任务，才能验证停止中排空队列。
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (stopper.isAlive && stopper.state != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertEquals(Thread.State.WAITING, stopper.state, "stop must wait for the running task")
            assertFalse(looper.post { error("stopping looper must reject new work") })
        } finally {
            release.countDown()
            stopper.join(5_000)
        }

        assertFalse(stopper.isAlive)
        assertNull(stopFailure.get())
        assertEquals(listOf("running", "queued", "finalizer"), order)
    }

    @Test
    fun `stopped looper rejects new synchronous and asynchronous work`() = runBlocking {
        val looper = Looper("looper-stopped-test").apply { start() }
        assertEquals(42, withTimeout(1_000) { looper.suspendAwait { 42 } })

        looper.stop()

        assertFalse(looper.post { error("stopped looper must not run posted work") })
        assertFalse(looper.signalCriticalSweep())
        assertFailsWith<IllegalStateException> {
            withTimeout(1_000) { looper.suspendAwait { error("must not run") } }
        }
    }
}
