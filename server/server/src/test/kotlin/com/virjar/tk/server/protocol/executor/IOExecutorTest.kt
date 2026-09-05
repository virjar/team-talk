package com.virjar.tk.server.protocol.executor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame

class IOExecutorTest {

    @Test
    fun `authentication ceiling reserves at least half of production-sized worker capacity`() {
        val fourWorkers = IOExecutor(workerCount = 4, queueCapacity = 1)
        val thirtyTwoWorkers = IOExecutor(workerCount = 32, queueCapacity = 1)
        try {
            assertEquals(2, fourWorkers.authenticationConcurrencyCeiling)
            assertEquals(16, thirtyTwoWorkers.authenticationConcurrencyCeiling)
        } finally {
            fourWorkers.shutdown()
            thirtyTwoWorkers.shutdown()
        }
    }

    @Test
    fun `task completion runs exactly once after normal execution`() = runBlocking {
        val executor = IOExecutor(workerCount = 1, queueCapacity = 1)
        val lease = AgentTaskLease("normal-completion")
        val ran = CompletableDeferred<Unit>()
        val completions = AtomicInteger(0)
        try {
            assertTrue(
                executor.tryLaunchTask(
                    lease = lease,
                    onCompletion = {
                        completions.incrementAndGet()
                        throw IllegalStateException("expected completion failure")
                    },
                ) { ran.complete(Unit) },
            )
            ran.await()
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }
            assertEquals(1, completions.get())
            val workerStillAlive = CompletableDeferred<Unit>()
            assertTrue(executor.tryLaunchTask { workerStillAlive.complete(Unit) })
            workerStillAlive.await()
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `cancelled asynchronous completion does not retire the long lived worker`() = runBlocking {
        val executor = IOExecutor(workerCount = 1, queueCapacity = 1)
        val ran = CompletableDeferred<Unit>()
        val cancellation = kotlinx.coroutines.CancellationException("completion owner cancelled")
        try {
            assertTrue(
                executor.tryLaunchTask(
                    lease = AgentTaskLease("cancelled-worker-completion"),
                    onCompletion = { throw cancellation },
                ) { ran.complete(Unit) },
            )
            ran.await()
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }

            val reused = CompletableDeferred<Unit>()
            assertTrue(executor.tryLaunchTask { reused.complete(Unit) })
            reused.await()
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `global queue rejects overload without running work on the caller and recovers capacity`() = runBlocking {
        val executor = IOExecutor(workerCount = 1, queueCapacity = 1)
        val gate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val rejectedRan = AtomicBoolean(false)

        try {
            assertTrue(executor.tryLaunchTask {
                firstStarted.complete(Unit)
                gate.await()
            })
            firstStarted.await()

            // 一个 worker 正在执行、一个任务在有界 Channel 中等待，第三个必须同步拒绝。
            assertTrue(executor.tryLaunchTask { gate.await() })
            assertFalse(executor.tryLaunchTask { rejectedRan.set(true) })
            assertFalse(rejectedRan.get())
            assertEquals(2, executor.outstandingTaskCount)

            gate.complete(Unit)
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }

            val recovered = CompletableDeferred<Unit>()
            assertTrue(executor.tryLaunchTask { recovered.complete(Unit) })
            recovered.await()
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }
        } finally {
            executor.shutdown()
        }

        assertFalse(executor.tryLaunchTask { error("shutdown 后不得执行") })
    }

    @Test
    fun `concurrent burst cannot admit more decoded tasks than workers plus queue capacity`() = runBlocking {
        val executor = IOExecutor(workerCount = 2, queueCapacity = 3)
        val gate = CompletableDeferred<Unit>()
        val startedWorkers = AtomicInteger(0)
        val bothWorkersStarted = CompletableDeferred<Unit>()

        try {
            repeat(2) {
                assertTrue(executor.tryLaunchTask {
                    if (startedWorkers.incrementAndGet() == 2) bothWorkersStarted.complete(Unit)
                    gate.await()
                })
            }
            bothWorkersStarted.await()

            val admissions = coroutineScope {
                List(32) {
                    async(Dispatchers.Default) {
                        executor.tryLaunchTask { gate.await() }
                    }
                }.awaitAll()
            }

            assertEquals(3, admissions.count { it })
            assertEquals(5, executor.outstandingTaskCount)
        } finally {
            gate.complete(Unit)
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }
            executor.shutdown()
        }
    }

    @Test
    fun `queued connection work is discarded after disconnect without executing`() = runBlocking {
        val executor = IOExecutor(workerCount = 1, queueCapacity = 1)
        val workerGate = CompletableDeferred<Unit>()
        val workerStarted = CompletableDeferred<Unit>()
        val queuedRan = AtomicBoolean(false)
        val completionCount = AtomicInteger(0)
        val lease = AgentTaskLease("queued-session")

        try {
            assertTrue(executor.tryLaunchTask {
                workerStarted.complete(Unit)
                workerGate.await()
            })
            workerStarted.await()
            assertTrue(
                executor.tryLaunchTask(
                    lease = lease,
                    onCompletion = { completionCount.incrementAndGet() },
                ) { queuedRan.set(true) },
            )

            lease.cancel()
            workerGate.complete(Unit)
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }

            assertFalse(queuedRan.get())
            assertEquals(1, completionCount.get(), "discarded work must release transferred admission")
        } finally {
            workerGate.complete(Unit)
            executor.shutdown()
        }
    }

    @Test
    fun `rejected submission completes transferred cleanup exactly once`() = runBlocking {
        val executor = IOExecutor(workerCount = 1, queueCapacity = 1)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val rejectedRan = AtomicBoolean(false)
        val completions = AtomicInteger(0)
        try {
            assertTrue(executor.tryLaunchTask { started.complete(Unit); gate.await() })
            started.await()
            assertTrue(executor.tryLaunchTask { gate.await() })
            assertFalse(
                executor.tryLaunchTask(
                    lease = AgentTaskLease("rejected-completion"),
                    onCompletion = { completions.incrementAndGet() },
                ) { rejectedRan.set(true) },
            )
            assertEquals(1, completions.get())
            assertFalse(rejectedRan.get())
        } finally {
            gate.complete(Unit)
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }
            executor.shutdown()
        }
    }

    @Test
    fun `rejected completion propagates exact cancellation without corrupting executor capacity`() = runBlocking {
        val executor = IOExecutor(workerCount = 1, queueCapacity = 1)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val cancellation = kotlinx.coroutines.CancellationException("completion owner cancelled")
        try {
            assertTrue(executor.tryLaunchTask { started.complete(Unit); gate.await() })
            started.await()
            assertTrue(executor.tryLaunchTask { gate.await() })

            val observed = try {
                executor.tryLaunchTask(
                    lease = AgentTaskLease("cancelled-rejected-completion"),
                    onCompletion = { throw cancellation },
                ) { error("rejected task must not run") }
                null
            } catch (failure: kotlinx.coroutines.CancellationException) {
                failure
            }
            assertSame(cancellation, observed)

            gate.complete(Unit)
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }
            val recovered = CompletableDeferred<Unit>()
            assertTrue(executor.tryLaunchTask { recovered.complete(Unit) })
            recovered.await()
        } finally {
            gate.complete(Unit)
            executor.shutdown()
        }
    }

    @Test
    fun `shutdown completes cleanup for queued work it abandons`() {
        val executor = IOExecutor(workerCount = 1, queueCapacity = 1)
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        val queuedRan = AtomicBoolean(false)
        val completions = AtomicInteger(0)
        val shutdownFailure = AtomicReference<Throwable?>(null)
        assertTrue(
            executor.tryLaunchTask {
                runningStarted.countDown()
                releaseRunning.await()
            },
        )
        assertTrue(runningStarted.await(1, TimeUnit.SECONDS))
        assertTrue(
            executor.tryLaunchTask(
                lease = AgentTaskLease("shutdown-completion"),
                onCompletion = { completions.incrementAndGet() },
            ) { queuedRan.set(true) },
        )

        val shutdown = Thread {
            runCatching { executor.shutdown() }.onFailure(shutdownFailure::set)
        }
        shutdown.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (completions.get() == 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1, completions.get())
            assertFalse(queuedRan.get())
        } finally {
            releaseRunning.countDown()
            shutdown.join(5_000)
        }
        assertFalse(shutdown.isAlive)
        assertEquals(null, shutdownFailure.get())
        assertEquals(1, completions.get())
    }

    @Test
    fun `shutdown drains every completion before replaying exact cancellation failure`() {
        val executor = IOExecutor(workerCount = 1, queueCapacity = 2)
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        val completions = AtomicInteger(0)
        val cancellation = kotlinx.coroutines.CancellationException("abandoned completion cancelled")
        val shutdownFailure = AtomicReference<Throwable?>(null)
        assertTrue(
            executor.tryLaunchTask {
                runningStarted.countDown()
                releaseRunning.await()
            },
        )
        assertTrue(runningStarted.await(1, TimeUnit.SECONDS))
        assertTrue(
            executor.tryLaunchTask(
                lease = AgentTaskLease("cancelled-abandoned-completion"),
                onCompletion = {
                    completions.incrementAndGet()
                    throw cancellation
                },
            ) { error("shutdown must abandon queued work") },
        )
        assertTrue(
            executor.tryLaunchTask(
                lease = AgentTaskLease("following-abandoned-completion"),
                onCompletion = { completions.incrementAndGet() },
            ) { error("shutdown must abandon queued work") },
        )

        val shutdown = Thread {
            runCatching { executor.shutdown() }.onFailure(shutdownFailure::set)
        }
        shutdown.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (completions.get() < 2 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(2, completions.get())
        } finally {
            releaseRunning.countDown()
            shutdown.join(5_000)
        }

        assertFalse(shutdown.isAlive)
        assertSame(cancellation, shutdownFailure.get())
        assertEquals(0, executor.outstandingTaskCount)
        assertFalse(executor.tryLaunchTask { error("closed executor must reject work") })
    }

    @Test
    fun `cancelling one connection task leaves its long lived worker reusable`() = runBlocking {
        val executor = IOExecutor(workerCount = 1, queueCapacity = 1)
        val lease = AgentTaskLease("running-session")
        val taskStarted = CompletableDeferred<Unit>()
        val taskFinished = CompletableDeferred<Unit>()

        try {
            assertTrue(executor.tryLaunchTask(lease) {
                taskStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    taskFinished.complete(Unit)
                }
            })
            taskStarted.await()
            lease.cancel()
            taskFinished.await()
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }

            val nextTaskRan = CompletableDeferred<Unit>()
            assertTrue(executor.tryLaunchTask { nextTaskRan.complete(Unit) })
            nextTaskRan.await()
            withTimeout(5_000) {
                while (executor.outstandingTaskCount != 0) delay(10)
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `shutdown reports an interrupt ignoring task instead of pretending the pool stopped`() {
        val executor = IOExecutor(workerCount = 1, queueCapacity = 1, shutdownTimeoutMillis = 20)
        val taskStarted = CountDownLatch(1)
        val releaseTask = AtomicBoolean(false)
        val taskExited = CountDownLatch(1)
        assertTrue(
            executor.tryLaunchTask {
                taskStarted.countDown()
                try {
                    while (!releaseTask.get()) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            // 模拟一个忽略强制线程池中断的阻塞依赖。
                        }
                    }
                } finally {
                    taskExited.countDown()
                }
            },
        )
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS))

        try {
            assertFailsWith<IllegalStateException> { executor.shutdown() }
        } finally {
            releaseTask.set(true)
            assertTrue(taskExited.await(1, TimeUnit.SECONDS))
        }
    }
}
