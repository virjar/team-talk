package com.virjar.tk.server.runtime

import com.virjar.tk.server.env.ThreadIOGuard
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpBlockingExecutorTest {
    @Test
    fun `executing plus queued capacity is exact and rejected blocks never run`() = runBlocking {
        val executor = HttpBlockingExecutor(workerCount = 1, queueCapacity = 1)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val rejectedRan = AtomicBoolean(false)
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                executor.tryExecute {
                    firstEntered.countDown()
                    releaseFirst.await()
                    "first"
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                executor.tryExecute { "second" }
            }

            assertEquals(2, executor.outstandingTaskCount)
            val rejected = executor.tryExecute {
                rejectedRan.set(true)
                "rejected"
            }
            assertEquals(HttpBlockingExecution.Rejected, rejected)
            assertFalse(rejectedRan.get())

            releaseFirst.countDown()
            assertEquals("first", assertCompleted(first.await()))
            assertEquals("second", assertCompleted(second.await()))
            assertEquals(0, executor.outstandingTaskCount)
        } finally {
            releaseFirst.countDown()
            executor.close()
        }
    }

    @Test
    fun `worker is unprotected even when the caller is a protected thread`() {
        val executor = HttpBlockingExecutor(workerCount = 1, queueCapacity = 1)
        ThreadIOGuard.protectCurrentThread()
        try {
            val workerName = runBlocking {
                assertCompleted(
                    executor.tryExecute {
                        ThreadIOGuard.check("HTTP blocking worker probe")
                        Thread.currentThread().name
                    },
                )
            }
            assertTrue(workerName.startsWith("teamtalk-http-blocking-"))
            assertFailsWith<IllegalStateException> {
                ThreadIOGuard.check("protected caller probe")
            }
        } finally {
            ThreadIOGuard.unprotectCurrentThread()
            executor.close()
        }
    }

    @Test
    fun `close stops admission drains accepted work and joins its worker`() = runBlocking {
        val executor = HttpBlockingExecutor(
            workerCount = 1,
            queueCapacity = 1,
            shutdownTimeoutMillis = 5_000,
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>(null)
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            executor.tryExecute {
                entered.countDown()
                release.await()
                "drained"
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val closer = Thread({
            closeFailure.set(runCatching { executor.close() }.exceptionOrNull())
            closeReturned.countDown()
        }, "http-blocking-close-test")
        closer.start()
        withTimeout(5_000) {
            while (executor.acceptsNewTasks) yield()
        }

        assertFalse(closeReturned.await(0, TimeUnit.MILLISECONDS))
        assertEquals(HttpBlockingExecution.Rejected, executor.tryExecute { "late" })
        release.countDown()

        assertEquals("drained", assertCompleted(work.await()))
        assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
        closer.join(5_000)
        assertFalse(closer.isAlive)
        assertNull(closeFailure.get())
        executor.close()
    }

    @Test
    fun `failed work releases its admission slot`() = runBlocking {
        val executor = HttpBlockingExecutor(workerCount = 1, queueCapacity = 1)
        try {
            assertFailsWith<DeliberateWorkFailure> {
                executor.tryExecute<String> { throw DeliberateWorkFailure() }
            }
            assertEquals("next", assertCompleted(executor.tryExecute { "next" }))
        } finally {
            executor.close()
        }
    }

    @Test
    fun `repeated close waits for the owner and rethrows the same terminal failure`() = runBlocking {
        supervisorScope {
            val executor = HttpBlockingExecutor(
                workerCount = 1,
                queueCapacity = 1,
                shutdownTimeoutMillis = 10,
            )
            val entered = CountDownLatch(1)
            val neverReleased = CountDownLatch(1)
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                executor.tryExecute {
                    entered.countDown()
                    neverReleased.await()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val first = assertFailsWith<HttpBlockingExecutorShutdownException> { executor.close() }
            val repeated = assertFailsWith<HttpBlockingExecutorShutdownException> { executor.close() }

            assertSame(first, repeated)
            assertFailsWith<InterruptedException> { work.await() }
        }
    }

    @Test
    fun `cancellation ignoring HTTP worker keeps FileStore class dependencies open`() = runBlocking {
        supervisorScope {
            val executor = HttpBlockingExecutor(
                workerCount = 1,
                queueCapacity = 1,
                shutdownTimeoutMillis = 10,
            )
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val dependencyClosed = AtomicBoolean(false)
            val owner = ServerResourceOwner { _, _ -> }
            owner.own("FileStore") { dependencyClosed.set(true) }
            owner.ownDependencyBarrier(
                name = "HTTP blocking executor",
                resource = executor,
                close = HttpBlockingExecutor::close,
                dependenciesMayClose = HttpBlockingExecutor::workersTerminated,
            )
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                executor.tryExecute {
                    entered.countDown()
                    while (true) {
                        try {
                            if (release.await(1, TimeUnit.SECONDS)) break
                        } catch (_: InterruptedException) {
                            // 模拟一个忽略协程与线程中断的阻塞驱动器。
                        }
                    }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            try {
                val terminal = assertIs<ServerResourceCloseException>(
                    runCatching { owner.close() }.exceptionOrNull(),
                )
                assertIs<HttpBlockingExecutorShutdownException>(terminal.failures.single().error)
                assertFalse(executor.workersTerminated)
                assertFalse(dependencyClosed.get(), "active HTTP work must retain old dependencies")
                assertSame(terminal, runCatching { owner.close() }.exceptionOrNull())
            } finally {
                release.countDown()
                withTimeout(5_000) { work.await() }
                withTimeout(5_000) {
                    while (!executor.workersTerminated) yield()
                }
            }

            assertFalse(dependencyClosed.get(), "a failed owner drain must never resume later")
        }
    }

    private fun <T> assertCompleted(execution: HttpBlockingExecution<T>): T =
        assertIs<HttpBlockingExecution.Completed<T>>(execution).value

    private class DeliberateWorkFailure : RuntimeException()
}
