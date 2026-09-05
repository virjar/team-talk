package com.virjar.tk.server.application

import com.virjar.tk.server.domain.presence.PresenceObserverLease
import com.virjar.tk.server.domain.presence.PresenceTransition
import com.virjar.tk.server.domain.presence.PresenceTransitionObserver
import com.virjar.tk.server.domain.presence.PresenceTransitionSource
import com.virjar.tk.server.runtime.BoundedCloseTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceCoordinatorTest {
    @Test
    fun `one hundred thousand changes for one uid stay bounded at the latest state`() {
        val mailbox = PresenceChangeMailbox(capacity = 4)

        repeat(100_000) { index ->
            assertEquals(
                PresenceOfferResult.ACCEPTED,
                mailbox.offer(PresenceChange(uid = "alice", online = index % 2 == 0)),
            )
        }

        assertEquals(1, mailbox.pendingSize)
        assertEquals(0L, mailbox.droppedNewUidCount)
        assertEquals(PresenceChange(uid = "alice", online = false), mailbox.poll())
        mailbox.complete("alice")
        assertNull(mailbox.poll())
    }

    @Test
    fun `updated uid moves behind retained peers and remains admissible at capacity`() {
        val mailbox = PresenceChangeMailbox(capacity = 2)

        assertEquals(PresenceOfferResult.ACCEPTED, mailbox.offer(PresenceChange("alice", online = true)))
        assertEquals(PresenceOfferResult.ACCEPTED, mailbox.offer(PresenceChange("bob", online = true)))
        assertEquals(PresenceOfferResult.ACCEPTED, mailbox.offer(PresenceChange("alice", online = false)))
        assertEquals(PresenceOfferResult.DROPPED_CAPACITY, mailbox.offer(PresenceChange("carol", online = true)))

        assertEquals(2, mailbox.pendingSize)
        assertEquals(1L, mailbox.droppedNewUidCount)
        assertEquals(PresenceChange("bob", online = true), mailbox.poll())
        mailbox.complete("bob")
        assertEquals(PresenceChange("alice", online = false), mailbox.poll())
        mailbox.complete("alice")
        assertNull(mailbox.poll())
    }

    @Test
    fun `coalescing retains the selected transition revision and occurrence time`() {
        val mailbox = PresenceChangeMailbox(capacity = 2)
        val superseded = PresenceChange("alice", online = true, occurredAt = 10L, revision = 7L)
        val retained = PresenceChange("alice", online = false, occurredAt = 20L, revision = 8L)

        assertEquals(PresenceOfferResult.ACCEPTED, mailbox.offer(superseded))
        assertEquals(PresenceOfferResult.ACCEPTED, mailbox.offer(retained))

        assertEquals(retained, mailbox.poll())
        mailbox.complete("alice")
    }

    @Test
    fun `in-flight uid keeps its capacity slot and can always enqueue its successor`() {
        val mailbox = PresenceChangeMailbox(capacity = 2)

        assertEquals(PresenceOfferResult.ACCEPTED, mailbox.offer(PresenceChange("alice", online = true)))
        assertEquals(PresenceChange("alice", online = true), mailbox.poll())
        assertEquals(1, mailbox.occupiedUidCount)

        assertEquals(PresenceOfferResult.ACCEPTED, mailbox.offer(PresenceChange("bob", online = true)))
        assertEquals(PresenceOfferResult.ACCEPTED, mailbox.offer(PresenceChange("alice", online = false)))
        assertEquals(PresenceOfferResult.DROPPED_CAPACITY, mailbox.offer(PresenceChange("carol", online = true)))

        assertEquals(2, mailbox.pendingSize)
        assertEquals(2, mailbox.occupiedUidCount)
        assertEquals(1L, mailbox.droppedNewUidCount)
        mailbox.complete("alice")
        assertEquals(PresenceChange("bob", online = true), mailbox.poll())
        mailbox.complete("bob")
        assertEquals(PresenceChange("alice", online = false), mailbox.poll())
        mailbox.complete("alice")
        assertEquals(0, mailbox.occupiedUidCount)
    }

    @Test
    fun `mailbox close clears waiting state rejects late callbacks and lets in-flight bookkeeping finish`() {
        val mailbox = PresenceChangeMailbox(capacity = 2)
        assertEquals(PresenceOfferResult.ACCEPTED, mailbox.offer(PresenceChange("alice", online = true)))
        assertEquals(PresenceOfferResult.ACCEPTED, mailbox.offer(PresenceChange("bob", online = true)))
        assertEquals(PresenceChange("alice", online = true), mailbox.poll())

        mailbox.close()
        mailbox.close()

        assertEquals(0, mailbox.pendingSize)
        assertEquals(1, mailbox.occupiedUidCount)
        assertEquals(PresenceOfferResult.CLOSED, mailbox.offer(PresenceChange("bob", online = true)))
        mailbox.complete("alice")
        assertEquals(0, mailbox.occupiedUidCount)
        assertNull(mailbox.poll())
    }

    @Test
    fun `successor for an in-flight uid is delivered after its current transition`() = runTest {
        val transitions = FakePresenceTransitionSource()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val delivered = mutableListOf<PresenceTransition>()
        val coordinator = PresenceCoordinator(
            transitions = transitions,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            mailboxCapacity = 4,
            broadcastPresence = { transition ->
                delivered += transition
                if (delivered.size == 1) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
            },
        )

        try {
            coordinator.start()
            transitions.emit("alice", online = true)
            firstEntered.await()

            transitions.emit("alice", online = false)
            assertEquals(1, coordinator.pendingChangeCount)

            releaseFirst.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    PresenceChange("alice", online = true),
                    PresenceChange("alice", online = false),
                ),
                delivered,
            )
        } finally {
            releaseFirst.complete(Unit)
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun `full mailbox admits the in-flight uid successor but drops a new uid`() = runTest {
        val transitions = FakePresenceTransitionSource()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val delivered = mutableListOf<PresenceTransition>()
        val coordinator = PresenceCoordinator(
            transitions = transitions,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            mailboxCapacity = 2,
            broadcastPresence = { transition ->
                delivered += transition
                if (delivered.size == 1) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
            },
        )

        try {
            coordinator.start()
            transitions.emit("alice", online = true)
            firstEntered.await()

            transitions.emit("bob", online = true)
            transitions.emit("alice", online = false)
            transitions.emit("carol", online = true)

            assertEquals(2, coordinator.pendingChangeCount)
            assertEquals(1L, coordinator.droppedChangeCount)

            releaseFirst.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    PresenceChange("alice", online = true),
                    PresenceChange("bob", online = true),
                    PresenceChange("alice", online = false),
                ),
                delivered,
            )
        } finally {
            releaseFirst.complete(Unit)
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun `one failed broadcast does not stop later retained transitions`() = runTest {
        val transitions = FakePresenceTransitionSource()
        val attempted = mutableListOf<PresenceTransition>()
        val coordinator = PresenceCoordinator(
            transitions = transitions,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            mailboxCapacity = 4,
            broadcastPresence = { transition ->
                attempted += transition
                if (transition.uid == "broken") error("expected broadcast failure")
            },
        )

        try {
            coordinator.start()
            transitions.emit("broken", online = true)
            transitions.emit("healthy", online = false)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    PresenceChange("broken", online = true),
                    PresenceChange("healthy", online = false),
                ),
                attempted,
            )
        } finally {
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun `close cancels in-flight fan-out unbinds callbacks and is terminal`() = runTest {
        val transitions = FakePresenceTransitionSource()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var attempts = 0
        val coordinator = PresenceCoordinator(
            transitions = transitions,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            mailboxCapacity = 4,
            broadcastPresence = { _ ->
                attempts += 1
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )

        try {
            coordinator.start()
            val staleObserver = transitions.currentObserver
            staleObserver.onTransition(PresenceChange("alice", online = true))
            entered.await()

            coordinator.closeAndJoin()

            assertTrue(cancelled.isCompleted)
            assertFalse(transitions.hasObserver)
            assertEquals(1, transitions.uninstallCount)
            staleObserver.onTransition(PresenceChange("late", online = true))
            assertEquals(0, coordinator.pendingChangeCount)
            assertEquals(1, attempts)
            coordinator.closeAndJoin()
            assertEquals(1, transitions.uninstallCount)
            assertFailsWith<IllegalStateException> { coordinator.start() }
        } finally {
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun `close joins the worker before propagating observer uninstall failure`() = runTest {
        val expected = IllegalStateException("expected uninstall failure")
        val transitions = FakePresenceTransitionSource(uninstallFailure = expected)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val coordinator = PresenceCoordinator(
            transitions = transitions,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            mailboxCapacity = 4,
            broadcastPresence = { _ ->
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )

        coordinator.start()
        transitions.emit("alice", online = true)
        entered.await()

        val actual = assertFailsWith<IllegalStateException> { coordinator.closeAndJoin() }

        assertSame(expected, actual)
        assertTrue(cancelled.isCompleted)
        assertFalse(transitions.hasObserver)
        assertSame(expected, runCatching { coordinator.closeAndJoin() }.exceptionOrNull())
    }

    @Test
    fun `one owner deadline releases concurrent close follower when worker ignores cancellation`() {
        val transitions = FakePresenceTransitionSource()
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerExited = CountDownLatch(1)
        val callersReady = CountDownLatch(2)
        val startClose = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val followerDone = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>(null)
        val followerFailure = AtomicReference<Throwable?>(null)
        val dispatcher = daemonDispatcher("presence-close-deadline-worker")
        val coordinator = PresenceCoordinator(
            transitions = transitions,
            workerDispatcher = dispatcher,
            mailboxCapacity = 4,
            broadcastPresence = { _ ->
                workerEntered.countDown()
                try {
                    // 故意阻塞：协程取消无法释放这个驱动器。
                    check(releaseWorker.await(5, TimeUnit.SECONDS)) { "test did not release worker" }
                } finally {
                    workerExited.countDown()
                }
            },
            shutdownTimeoutMillis = 500L,
        )
        coordinator.start()
        transitions.emit("alice", online = true)
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS))

        val first = thread(isDaemon = true, name = "presence-close-owner") {
            callersReady.countDown()
            check(startClose.await(5, TimeUnit.SECONDS)) { "test did not start close" }
            firstFailure.set(runCatching { runBlocking { coordinator.closeAndJoin() } }.exceptionOrNull())
            firstDone.countDown()
        }
        val follower = thread(isDaemon = true, name = "presence-close-follower") {
            callersReady.countDown()
            check(startClose.await(5, TimeUnit.SECONDS)) { "test did not start close" }
            followerFailure.set(runCatching { coordinator.close() }.exceptionOrNull())
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
            assertSame(terminal, runCatching { coordinator.close() }.exceptionOrNull())
            assertTrue(coordinator.isStopped)
            assertSame(terminal, coordinator.closeTerminalFailure)
            assertFalse(transitions.hasObserver)
            assertEquals(1, transitions.uninstallCount)
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
    fun `worker fatal remains primary and observer uninstall failure is replayed as suppressed`() {
        val fatal = FatalProbe("presence worker fatal")
        val uninstallFailure = IllegalStateException("observer uninstall failed")
        val transitions = FakePresenceTransitionSource(uninstallFailure = uninstallFailure)
        val workerEntered = CountDownLatch(1)
        val dispatcher = daemonDispatcher("presence-fatal-worker")
        val coordinator = PresenceCoordinator(
            transitions = transitions,
            workerDispatcher = dispatcher,
            mailboxCapacity = 4,
            broadcastPresence = { _ ->
                workerEntered.countDown()
                throw fatal
            },
            shutdownTimeoutMillis = 1_000L,
        )

        try {
            coordinator.start()
            transitions.emit("alice", online = true)
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))

            val terminal = assertFailsWith<FatalProbe> { coordinator.close() }

            assertSame(fatal, terminal)
            assertTrue(terminal.suppressed.any { it === uninstallFailure })
            assertSame(
                terminal,
                runCatching { runBlocking { coordinator.closeAndJoin() } }.exceptionOrNull(),
            )
            assertTrue(coordinator.isStopped)
            assertSame(terminal, coordinator.closeTerminalFailure)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `observer installation failure closes the worker lifecycle and is replayed`() {
        val expected = IllegalStateException("observer installation failed")
        val transitions = FakePresenceTransitionSource(installFailure = expected)
        val coordinator = PresenceCoordinator(
            transitions = transitions,
            workerDispatcher = UnconfinedTestDispatcher(),
            mailboxCapacity = 4,
            broadcastPresence = { _ -> },
            shutdownTimeoutMillis = 1_000L,
        )

        val terminal = assertFailsWith<IllegalStateException> { coordinator.start() }

        assertSame(expected, terminal)
        assertFalse(transitions.hasObserver)
        assertTrue(coordinator.isStopped)
        assertSame(terminal, coordinator.closeTerminalFailure)
        assertSame(terminal, runCatching { coordinator.close() }.exceptionOrNull())
        assertFailsWith<IllegalStateException> { coordinator.start() }
    }

    @Test
    fun `drop reporting thresholds grow geometrically without overflow`() {
        assertEquals(2L, nextPresenceDropReportThreshold(1L))
        assertEquals(4L, nextPresenceDropReportThreshold(2L))
        assertEquals(4L, nextPresenceDropReportThreshold(3L))
        assertEquals(Long.MAX_VALUE, nextPresenceDropReportThreshold(Long.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { nextPresenceDropReportThreshold(0L) }
    }

    private class FakePresenceTransitionSource(
        private val uninstallFailure: Throwable? = null,
        private val installFailure: Throwable? = null,
    ) : PresenceTransitionSource {
        private var observer: PresenceTransitionObserver? = null
        var uninstallCount: Int = 0
            private set

        val hasObserver: Boolean get() = observer != null
        val currentObserver: PresenceTransitionObserver get() = requireNotNull(observer)

        override fun installPresenceObserver(observer: PresenceTransitionObserver): PresenceObserverLease {
            installFailure?.let { throw it }
            check(this.observer == null) { "observer already installed" }
            this.observer = observer
            var uninstalled = false
            return PresenceObserverLease {
                if (!uninstalled) {
                    uninstalled = true
                    uninstallCount += 1
                    if (this.observer === observer) this.observer = null
                    uninstallFailure?.let { throw it }
                }
            }
        }

        fun emit(uid: String, online: Boolean) {
            currentObserver.onTransition(PresenceChange(uid, online))
        }
    }

    private fun daemonDispatcher(name: String): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { command ->
            Thread(command, name).apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private class FatalProbe(message: String) : Error(message)
}

@Suppress("FunctionName")
private fun PresenceChange(
    uid: String,
    online: Boolean,
    occurredAt: Long = 1L,
    revision: Long = 1L,
): PresenceTransition = PresenceTransition(
    uid = uid,
    online = online,
    occurredAt = occurredAt,
    serverEpoch = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    revision = revision,
)
