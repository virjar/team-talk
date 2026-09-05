package com.virjar.tk.server.infra.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HealthCheckSingleFlightTest {
    @Test
    fun `concurrent callers share only the active refresh`() = runBlocking {
        val refreshCount = AtomicInteger(0)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val expected = HealthResponse("UP", mapOf("postgres" to ComponentHealth("UP")), TEST_BUILD_IDENTITY)
        val singleFlight = HealthCheckSingleFlight()

        val leader = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.get {
                refreshCount.incrementAndGet()
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                expected
            }
        }
        refreshStarted.await()
        val follower = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.get {
                refreshCount.incrementAndGet()
                error("a concurrent cache miss must not start a second refresh")
            }
        }

        assertEquals(1, refreshCount.get())
        releaseRefresh.complete(Unit)
        assertSame(expected, leader.await())
        assertSame(expected, follower.await())

        val refreshed = HealthResponse("DOWN", emptyMap(), TEST_BUILD_IDENTITY)
        assertSame(
            refreshed,
            singleFlight.get {
                refreshCount.incrementAndGet()
                refreshed
            },
        )
        assertEquals(2, refreshCount.get())
    }

    @Test
    fun `caller cancellation while waiting for the shared refresh is preserved`() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val singleFlight = HealthCheckSingleFlight()
        val leader = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.get {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                HealthResponse("UP", emptyMap(), TEST_BUILD_IDENTITY)
            }
        }
        refreshStarted.await()
        val cancelledFollower = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.get { error("waiting caller must not become a refresh owner") }
        }

        cancelledFollower.cancel(CancellationException("caller stopped waiting"))
        assertFailsWith<CancellationException> { cancelledFollower.await() }
        assertTrue(leader.isActive, "a cancelled waiter must not cancel the shared refresh")

        releaseRefresh.complete(Unit)
        assertEquals("UP", leader.await().status)
    }

    @Test
    fun `refresh owner cancellation clears the active evaluation for retry`() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val singleFlight = HealthCheckSingleFlight()
        val retry = HealthResponse("UP", emptyMap(), TEST_BUILD_IDENTITY)
        val cancelledOwner = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.get {
                refreshStarted.complete(Unit)
                awaitCancellation()
            }
        }
        refreshStarted.await()
        val waitingCaller = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.get { retry }
        }

        cancelledOwner.cancel(CancellationException("health caller disconnected"))
        assertFailsWith<CancellationException> { cancelledOwner.await() }

        assertSame(retry, waitingCaller.await())
    }

    @Test
    fun `independent slow probes start concurrently`() = runBlocking {
        val started = List(3) { CompletableDeferred<Unit>() }
        val release = CompletableDeferred<Unit>()
        fun probe(index: Int, name: String): suspend () -> ComponentHealth = {
            started[index].complete(Unit)
            release.await()
            ComponentHealth("UP", name)
        }

        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runConcurrentExternalHealthProbes(
                postgres = probe(0, "postgres"),
                managedChatProjection = probe(1, "managed"),
                tcp = probe(2, "tcp"),
            )
        }
        withTimeout(1_000L) {
            started.forEach { it.await() }
        }
        release.complete(Unit)

        assertEquals("postgres", result.await().postgres.detail)
        assertEquals("managed", result.await().managedChatProjection.detail)
        assertEquals("tcp", result.await().tcp.detail)
    }
}

private const val TEST_BUILD_IDENTITY = "1.0.7+0123456789abcdef0123456789abcdef01234567"
