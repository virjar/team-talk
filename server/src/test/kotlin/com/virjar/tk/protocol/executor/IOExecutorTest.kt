package com.virjar.tk.protocol.executor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IOExecutorTest {

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
}
