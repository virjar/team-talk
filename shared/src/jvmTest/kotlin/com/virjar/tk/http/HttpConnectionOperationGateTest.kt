package com.virjar.tk.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpConnectionOperationGateTest {
    @Test
    fun `close after registration denies first IO and waits for operation exit`() {
        val gate = HttpConnectionOperationGate("test transport")
        val connection = TrackingConnection()
        val operation = gate.register(connection) { IOException("closed") }
        val closeResult = AtomicReference<Result<Unit>>()
        val closer = thread(name = "http-gate-close") {
            closeResult.set(runCatching(gate::close))
        }

        assertTrue(connection.disconnectEntered.await(5, TimeUnit.SECONDS))
        assertTrue(closer.isAlive, "close must still be joining the registered operation")
        val ioCalls = AtomicInteger()
        assertFailsWith<IOException> {
            operation.execute { ioCalls.incrementAndGet() }
        }

        closer.join(5_000)
        assertFalse(closer.isAlive, "close did not finish after the operation left")
        closeResult.get().getOrThrow()
        assertEquals(0, ioCalls.get(), "an operation sealed before first I/O reached the network body")
        assertEquals(1, connection.disconnectCalls.get())
        gate.close()
    }

    @Test
    fun `concurrent closers join and a disconnect failure does not skip other connections`() {
        val gate = HttpConnectionOperationGate("test transport")
        val first = TrackingConnection(disconnectFailure = IOException("first disconnect failed"))
        val second = TrackingConnection()
        val firstOperation = gate.register(first) { IOException("closed") }
        val secondOperation = gate.register(second) { IOException("closed") }
        val firstClose = AtomicReference<Result<Unit>>()
        val secondClose = AtomicReference<Result<Unit>>()

        val leader = thread(name = "http-gate-close-leader") {
            firstClose.set(runCatching(gate::close))
        }
        assertTrue(first.disconnectEntered.await(5, TimeUnit.SECONDS))
        assertTrue(second.disconnectEntered.await(5, TimeUnit.SECONDS))
        val follower = thread(name = "http-gate-close-follower") {
            secondClose.set(runCatching(gate::close))
        }

        assertTrue(leader.isAlive, "leader must join both registered operations")
        assertTrue(follower.isAlive, "a concurrent close must join the same boundary")
        assertFailsWith<IOException> { firstOperation.execute {} }
        assertFailsWith<IOException> { secondOperation.execute {} }

        leader.join(5_000)
        follower.join(5_000)
        assertFalse(leader.isAlive)
        assertFalse(follower.isAlive)
        assertIs<HttpConnectionCloseException>(firstClose.get().exceptionOrNull())
        assertIs<HttpConnectionCloseException>(secondClose.get().exceptionOrNull())
        assertEquals(1, first.disconnectCalls.get())
        assertEquals(1, second.disconnectCalls.get(), "later connections must still be disconnected")
        assertFailsWith<HttpConnectionCloseException> { gate.close() }
    }

    @Test
    fun `reentrant close seals and disconnects then throws instead of deadlocking`() {
        val gate = HttpConnectionOperationGate("test transport")
        val connection = TrackingConnection()
        val operation = gate.register(connection) { IOException("closed") }

        operation.execute {
            assertFailsWith<HttpConnectionReentrantCloseException> { gate.close() }
        }

        assertEquals(1, connection.disconnectCalls.get())
        gate.close()
        assertFailsWith<IOException> {
            gate.register(TrackingConnection()) { IOException("closed") }
        }
    }

    @Test
    fun `rejected registration reports its disconnect failure to the outer owner`() {
        val gate = HttpConnectionOperationGate("test transport")
        gate.close()
        val disconnectFailure = IOException("rejected cleanup failed")
        val connection = TrackingConnection(disconnectFailure = disconnectFailure)
        val reported = AtomicReference<Throwable?>()

        val admissionFailure = assertFailsWith<IOException> {
            gate.register(
                connection = connection,
                onRejectedDisconnectFailure = reported::set,
                closedFailure = { IOException("closed") },
            )
        }

        assertSame(disconnectFailure, reported.get())
        assertTrue(admissionFailure.suppressed.any { it === disconnectFailure })
        assertEquals(1, connection.disconnectCalls.get())
    }

    private class TrackingConnection(
        private val disconnectFailure: Throwable? = null,
    ) : HttpURLConnection(URL("https://example.test")) {
        val disconnectCalls = AtomicInteger()
        val disconnectEntered = CountDownLatch(1)

        override fun connect() = Unit

        override fun disconnect() {
            disconnectCalls.incrementAndGet()
            disconnectEntered.countDown()
            disconnectFailure?.let { throw it }
        }

        override fun usingProxy(): Boolean = false
    }
}
