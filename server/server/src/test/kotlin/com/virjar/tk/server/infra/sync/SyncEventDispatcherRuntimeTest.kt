package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.infra.health.syncEventDispatcherHealth
import com.virjar.tk.server.runtime.BoundedCloseTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncEventDispatcherRuntimeTest {
    @Test
    fun `mandatory startup uid batch propagates the first database boundary failure`() = runBlocking {
        val expected = IllegalStateException("mark dispatched failed")
        val attempted = mutableListOf<String>()
        val reported = mutableListOf<String>()

        val actual = assertFailsWith<IllegalStateException> {
            dispatchDurableUidBatch(
                uids = listOf("uid-1", "uid-2", "uid-3"),
                requireSuccess = true,
                dispatch = { uid ->
                    attempted += uid
                    if (uid == "uid-2") throw expected
                },
                onFailure = { uid, _ -> reported += uid },
            )
        }

        assertSame(expected, actual)
        assertEquals(listOf("uid-1", "uid-2"), attempted)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `steady state uid batch records one failure and continues later durable users`() = runBlocking {
        val attempted = mutableListOf<String>()
        val reported = mutableListOf<String>()

        dispatchDurableUidBatch(
            uids = listOf("uid-1", "uid-2", "uid-3"),
            requireSuccess = false,
            dispatch = { uid ->
                attempted += uid
                if (uid == "uid-2") error("temporary database failure")
            },
            onFailure = { uid, _ -> reported += uid },
        )

        assertEquals(listOf("uid-1", "uid-2", "uid-3"), attempted)
        assertEquals(listOf("uid-2"), reported)
    }

    @Test
    fun `readiness is published only after the mandatory durable scan succeeds`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var passes = 0
        val runtime = runtime { scanDatabase, requireScanSuccess ->
            passes += 1
            if (passes == 1) {
                assertTrue(scanDatabase)
                assertTrue(requireScanSuccess)
                entered.complete(Unit)
                release.await()
            }
        }

        runtime.start()
        entered.await()

        val starting = runtime.snapshot()
        assertEquals(SyncEventDispatcherPhase.STARTING, starting.phase)
        assertTrue(starting.live)
        assertFalse(starting.ready)
        assertEquals("DOWN", syncEventDispatcherHealth(starting).status)

        release.complete(Unit)
        runtime.awaitStartupScan()

        val ready = runtime.snapshot()
        assertEquals(SyncEventDispatcherPhase.READY, ready.phase)
        assertTrue(ready.live)
        assertTrue(ready.ready)
        assertNull(ready.detail)
        assertEquals("UP", syncEventDispatcherHealth(ready).status)
        runtime.close()
    }

    @Test
    fun `mandatory startup scan completes every bounded continuation before readiness`() = runBlocking {
        var passes = 0
        val runtime = SyncEventDispatcherRuntime(
            workerDispatcher = Dispatchers.Unconfined,
            scanIntervalMillis = 60_000L,
            runPass = { scanDatabase, requireScanSuccess ->
                passes += 1
                assertTrue(scanDatabase)
                assertTrue(requireScanSuccess)
                if (passes < 4) {
                    SyncEventDispatchPassResult.MORE_REQUIRED
                } else {
                    SyncEventDispatchPassResult.COMPLETE
                }
            },
        )

        runtime.start()
        runtime.awaitStartupScan()

        assertEquals(4, passes)
        assertEquals(SyncEventDispatcherPhase.READY, runtime.snapshot().phase)
        runtime.close()
    }

    @Test
    fun `steady overflow recovery schedules its next bounded worker turn`() = runBlocking {
        var passes = 0
        val recoveryCompleted = CompletableDeferred<Unit>()
        val runtime = SyncEventDispatcherRuntime(
            workerDispatcher = Dispatchers.Unconfined,
            scanIntervalMillis = 60_000L,
            runPass = { _, requireScanSuccess ->
                passes += 1
                when (passes) {
                    1 -> {
                        assertTrue(requireScanSuccess)
                        SyncEventDispatchPassResult.COMPLETE
                    }
                    2 -> {
                        assertFalse(requireScanSuccess)
                        SyncEventDispatchPassResult.MORE_REQUIRED
                    }
                    else -> {
                        assertFalse(requireScanSuccess)
                        recoveryCompleted.complete(Unit)
                        SyncEventDispatchPassResult.COMPLETE
                    }
                }
            },
        )

        runtime.start()
        runtime.awaitStartupScan()
        runtime.requestPass()
        recoveryCompleted.await()

        assertEquals(3, passes)
        runtime.close()
    }

    @Test
    fun `startup scan failure is fail closed and public health omits exception detail`() = runBlocking {
        val expected = IllegalStateException("jdbc://internal?password=do-not-leak")
        val runtime = runtime { _, _ -> throw expected }

        runtime.start()

        assertFailsWith<IllegalStateException> { runtime.awaitStartupScan() }
        val failed = runtime.snapshot()
        assertEquals(SyncEventDispatcherPhase.FAILED, failed.phase)
        assertFalse(failed.live)
        assertFalse(failed.ready)
        assertEquals("Durable sync startup scan failed", failed.detail)
        assertFalse(failed.detail.orEmpty().contains("password"))
        assertFalse(failed.detail.orEmpty().contains(expected::class.simpleName.orEmpty()))

        val component = syncEventDispatcherHealth(failed)
        assertEquals("DOWN", component.status)
        assertEquals("Durable sync startup scan failed", component.detail)
        assertSame(expected, runCatching { runtime.close() }.exceptionOrNull())
        assertSame(expected, runCatching { runtime.close() }.exceptionOrNull())
    }

    @Test
    fun `unrecoverable ordinary runtime failure lowers health without exposing its message`() = runBlocking {
        val expected = IllegalStateException("SELECT secret_runtime_column")
        var passes = 0
        val runtime = runtime { _, _ ->
            passes += 1
            if (passes > 1) throw expected
        }

        runtime.start()
        runtime.awaitStartupScan()
        runtime.requestPass()

        val failed = runtime.snapshot()
        assertEquals(SyncEventDispatcherPhase.FAILED, failed.phase)
        assertFalse(failed.live)
        assertFalse(failed.ready)
        assertEquals("DOWN", syncEventDispatcherHealth(failed).status)
        assertFalse(failed.detail.orEmpty().contains("secret_runtime_column"))
        assertSame(expected, runCatching { runtime.close() }.exceptionOrNull())
    }

    @Test
    fun `unrecoverable runtime fatal failure lowers liveness and is replayed on close`() = runBlocking {
        val fatal = FatalProbe("native payload should remain internal")
        var passes = 0
        val runtime = runtime { _, _ ->
            passes += 1
            if (passes > 1) throw fatal
        }

        runtime.start()
        runtime.awaitStartupScan()
        runtime.requestPass()

        val failed = runtime.snapshot()
        assertEquals(SyncEventDispatcherPhase.FAILED, failed.phase)
        assertFalse(failed.live)
        assertFalse(failed.ready)
        assertEquals("Durable sync dispatcher worker terminated", failed.detail)
        assertFalse(failed.detail.orEmpty().contains(fatal.message.orEmpty()))
        assertSame(fatal, runCatching { runtime.close() }.exceptionOrNull())
        assertSame(fatal, runCatching { runtime.close() }.exceptionOrNull())
    }

    @Test
    fun `caller cancellation does not complete or cancel dispatcher startup`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = runtime { _, _ ->
            entered.complete(Unit)
            release.await()
        }
        runtime.start()
        entered.await()

        val waiter = async { runtime.awaitStartupScan() }
        waiter.cancel(CancellationException("caller no longer waits"))
        assertFailsWith<CancellationException> { waiter.await() }
        assertEquals(SyncEventDispatcherPhase.STARTING, runtime.snapshot().phase)

        release.complete(Unit)
        runtime.awaitStartupScan()
        assertEquals(SyncEventDispatcherPhase.READY, runtime.snapshot().phase)
        runtime.close()
    }

    @Test
    fun `close cancels and joins an in-flight startup scan without recording a worker failure`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runtime = runtime { _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }
        runtime.start()
        entered.await()

        runtime.close()

        val stopped = runtime.snapshot()
        assertEquals(SyncEventDispatcherPhase.STOPPED, stopped.phase)
        assertFalse(stopped.live)
        assertFalse(stopped.ready)
        assertFailsWith<CancellationException> { runtime.awaitStartupScan() }
        runtime.close()
        assertFailsWith<IllegalStateException> { runtime.start() }
    }

    @Test
    fun `one owner deadline bounds cancellation-ignoring worker and concurrent close follower`() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerExited = CountDownLatch(1)
        val callersReady = CountDownLatch(2)
        val startClose = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>(null)
        val followerFailure = AtomicReference<Throwable?>(null)
        val firstDone = CountDownLatch(1)
        val followerDone = CountDownLatch(1)
        val dispatcher = daemonDispatcher("sync-close-deadline-worker")
        val runtime = SyncEventDispatcherRuntime(
            workerDispatcher = dispatcher,
            scanIntervalMillis = 60_000L,
            shutdownTimeoutMillis = 500L,
            runPass = { _, _ ->
                workerEntered.countDown()
                try {
                    // 一个阻塞驱动器，在被显式释放之前忽略协程取消。
                    check(releaseWorker.await(5, TimeUnit.SECONDS)) { "test did not release worker" }
                } finally {
                    workerExited.countDown()
                }
                SyncEventDispatchPassResult.COMPLETE
            },
        )
        runtime.start()
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS))

        val first = thread(isDaemon = true, name = "sync-close-owner") {
            callersReady.countDown()
            check(startClose.await(5, TimeUnit.SECONDS)) { "test did not start close" }
            firstFailure.set(runCatching { runtime.close() }.exceptionOrNull())
            firstDone.countDown()
        }
        val follower = thread(isDaemon = true, name = "sync-close-follower") {
            callersReady.countDown()
            check(startClose.await(5, TimeUnit.SECONDS)) { "test did not start close" }
            followerFailure.set(runCatching { runtime.close() }.exceptionOrNull())
            followerDone.countDown()
        }

        try {
            assertTrue(callersReady.await(5, TimeUnit.SECONDS))
            val closeStartedAt = System.nanoTime()
            startClose.countDown()
            assertTrue(firstDone.await(2, TimeUnit.SECONDS), "close owner exceeded its deadline")
            assertTrue(followerDone.await(2, TimeUnit.SECONDS), "close follower exceeded the owner deadline")
            val closeElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStartedAt)
            assertTrue(closeElapsedMillis < 2_000L, "close took ${closeElapsedMillis}ms")

            val terminal = assertIs<BoundedCloseTimeoutException>(firstFailure.get())
            assertSame(terminal, followerFailure.get())
            assertSame(terminal, runCatching { runtime.close() }.exceptionOrNull())
            assertEquals(SyncEventDispatcherPhase.STOPPED, runtime.snapshot().phase)
            assertFalse(runtime.acceptsSignals())
        } finally {
            releaseWorker.countDown()
            assertTrue(workerExited.await(5, TimeUnit.SECONDS), "worker did not exit after test release")
            first.join(5_000)
            follower.join(5_000)
            dispatcher.close()
        }

        assertFalse(first.isAlive)
        assertFalse(follower.isAlive)
    }

    @Test
    fun `interrupted close owner restores interrupt and replays the same failure`() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerExited = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>(null)
        val interruptRestored = AtomicReference(false)
        val dispatcher = daemonDispatcher("sync-interrupted-close-worker")
        val runtime = SyncEventDispatcherRuntime(
            workerDispatcher = dispatcher,
            scanIntervalMillis = 60_000L,
            shutdownTimeoutMillis = 2_000L,
            runPass = { _, _ ->
                workerEntered.countDown()
                try {
                    check(releaseWorker.await(5, TimeUnit.SECONDS)) { "test did not release worker" }
                } finally {
                    workerExited.countDown()
                }
                SyncEventDispatchPassResult.COMPLETE
            },
        )
        runtime.start()
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS))

        val closer = thread(isDaemon = true, name = "sync-interrupted-close-owner") {
            closeFailure.set(runCatching { runtime.close() }.exceptionOrNull())
            interruptRestored.set(Thread.currentThread().isInterrupted)
            closeDone.countDown()
        }

        try {
            assertTrue(awaitTimedWaiting(closer), "close owner did not begin its bounded wait")
            closer.interrupt()
            assertFalse(closeDone.await(100, TimeUnit.MILLISECONDS))
            releaseWorker.countDown()
            assertTrue(closeDone.await(3, TimeUnit.SECONDS))

            val terminal = assertIs<InterruptedException>(closeFailure.get())
            assertTrue(interruptRestored.get())
            assertSame(terminal, runCatching { runtime.close() }.exceptionOrNull())
        } finally {
            releaseWorker.countDown()
            assertTrue(workerExited.await(5, TimeUnit.SECONDS))
            closer.join(5_000)
            dispatcher.close()
        }
        assertFalse(closer.isAlive)
    }

    private fun runtime(
        runPass: suspend (scanDatabase: Boolean, requireScanSuccess: Boolean) -> Unit,
    ): SyncEventDispatcherRuntime = SyncEventDispatcherRuntime(
        workerDispatcher = Dispatchers.Unconfined,
        scanIntervalMillis = 60_000L,
        runPass = { scanDatabase, requireScanSuccess ->
            runPass(scanDatabase, requireScanSuccess)
            SyncEventDispatchPassResult.COMPLETE
        },
    )

    private fun daemonDispatcher(name: String): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { command ->
            Thread(command, name).apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private fun awaitTimedWaiting(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && thread.isAlive) {
            if (thread.state == Thread.State.TIMED_WAITING) return true
            Thread.yield()
        }
        return thread.state == Thread.State.TIMED_WAITING
    }

    private class FatalProbe(message: String) : Error(message)
}
