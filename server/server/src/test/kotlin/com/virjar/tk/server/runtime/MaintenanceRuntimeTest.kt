package com.virjar.tk.server.runtime

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaintenanceRuntimeTest {
    @Test
    fun `cooperative maintenance terminates before its dependencies close`() {
        val entered = CountDownLatch(1)
        val closed = mutableListOf<String>()
        val dispatcher = daemonDispatcher("maintenance-cooperative-worker")
        val runtime = MaintenanceRuntime(dispatcher, shutdownTimeoutMillis = 1_000L)
        val owner = ServerResourceOwner { _, _ -> }
        owner.own("database") { closed += "database" }
        owner.ownDependencyBarrier(
            name = "maintenance",
            resource = runtime,
            close = {
                closed += "maintenance"
                it.close()
            },
            dependenciesMayClose = MaintenanceRuntime::workersTerminated,
        )
        runtime.start(
            listOf(
                MaintenanceWorker("cooperative") {
                    entered.countDown()
                    awaitCancellation()
                },
            ),
        )

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            owner.close()

            assertTrue(runtime.workersTerminated)
            assertEquals(listOf("maintenance", "database"), closed)
            owner.close()
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `cancellation ignoring maintenance blocks dependency release after one bounded close`() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerExited = CountDownLatch(1)
        val callersReady = CountDownLatch(2)
        val startClose = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val followerDone = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>(null)
        val followerFailure = AtomicReference<Throwable?>(null)
        val dependencyClosed = AtomicReference(false)
        val dispatcher = daemonDispatcher("maintenance-blocking-worker")
        val runtime = MaintenanceRuntime(dispatcher, shutdownTimeoutMillis = 300L)
        val owner = ServerResourceOwner { _, _ -> }
        owner.own("database") { dependencyClosed.set(true) }
        owner.ownDependencyBarrier(
            name = "maintenance",
            resource = runtime,
            close = MaintenanceRuntime::close,
            dependenciesMayClose = MaintenanceRuntime::workersTerminated,
        )
        runtime.start(
            listOf(
                MaintenanceWorker("blocking-driver") {
                    workerEntered.countDown()
                    try {
                        // 模拟不配合协程取消的 JDBC/文件代码。
                        check(releaseWorker.await(5, TimeUnit.SECONDS)) { "test did not release worker" }
                    } finally {
                        workerExited.countDown()
                    }
                },
            ),
        )
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS))

        val first = thread(isDaemon = true, name = "maintenance-owner-close") {
            callersReady.countDown()
            check(startClose.await(5, TimeUnit.SECONDS)) { "test did not start close" }
            firstFailure.set(runCatching { owner.close() }.exceptionOrNull())
            firstDone.countDown()
        }
        val follower = thread(isDaemon = true, name = "maintenance-follower-close") {
            callersReady.countDown()
            check(startClose.await(5, TimeUnit.SECONDS)) { "test did not start close" }
            followerFailure.set(runCatching { owner.close() }.exceptionOrNull())
            followerDone.countDown()
        }

        try {
            assertTrue(callersReady.await(5, TimeUnit.SECONDS))
            startClose.countDown()
            assertTrue(firstDone.await(2, TimeUnit.SECONDS), "close owner exceeded its deadline")
            assertTrue(followerDone.await(2, TimeUnit.SECONDS), "close follower exceeded the owner deadline")

            val terminal = assertIs<ServerResourceCloseException>(firstFailure.get())
            assertSame(terminal, followerFailure.get())
            assertSame(terminal, runCatching { owner.close() }.exceptionOrNull())
            val runtimeTerminal = assertIs<BoundedCloseTimeoutException>(terminal.failures.single().error)
            assertSame(runtimeTerminal, runCatching { runtime.close() }.exceptionOrNull())
            assertFalse(runtime.workersTerminated)
            assertFalse(dependencyClosed.get(), "active maintenance must retain its dependencies")
        } finally {
            releaseWorker.countDown()
            assertTrue(workerExited.await(5, TimeUnit.SECONDS), "worker did not exit after test release")
            assertTrue(awaitCondition { runtime.workersTerminated })
            first.join(5_000L)
            follower.join(5_000L)
            dispatcher.close()
        }

        assertFalse(first.isAlive)
        assertFalse(follower.isAlive)
        assertFalse(dependencyClosed.get(), "failed owner drain must never resume behind callers")
    }

    @Test
    fun `ordinary worker failure is stable but permits safe dependency cleanup after termination`() {
        val expected = IllegalStateException("maintenance worker failed")
        val dependencyClosed = AtomicReference(false)
        val dispatcher = daemonDispatcher("maintenance-failing-worker")
        val runtime = MaintenanceRuntime(dispatcher, shutdownTimeoutMillis = 1_000L)
        val owner = ServerResourceOwner { _, _ -> }
        owner.own("database") { dependencyClosed.set(true) }
        owner.ownDependencyBarrier(
            name = "maintenance",
            resource = runtime,
            close = MaintenanceRuntime::close,
            dependenciesMayClose = MaintenanceRuntime::workersTerminated,
        )
        runtime.start(
            listOf(
                MaintenanceWorker("failing") { throw expected },
            ),
        )

        try {
            assertTrue(awaitCondition { runtime.workersTerminated })

            val ownerTerminal = assertIs<ServerResourceCloseException>(
                runCatching { owner.close() }.exceptionOrNull(),
            )

            assertSame(expected, ownerTerminal.failures.single().error)
            assertTrue(dependencyClosed.get())
            assertSame(expected, runCatching { runtime.close() }.exceptionOrNull())
            assertSame(ownerTerminal, runCatching { owner.close() }.exceptionOrNull())
        } finally {
            dispatcher.close()
        }
    }

    private fun daemonDispatcher(name: String): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { command ->
            Thread(command, name).apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private fun awaitCondition(predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.yield()
        }
        return predicate()
    }
}
