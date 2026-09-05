package com.virjar.tk.shared.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun `suspending operation cancellation disconnects and preserves the cancellation terminal`() = runBlocking {
        val gate = HttpConnectionOperationGate("test transport")
        val releaseBody = CountDownLatch(1)
        val connection = TrackingConnection(disconnectAction = releaseBody::countDown)
        val operation = gate.register(connection) { IOException("closed") }
        val bodyEntered = CountDownLatch(1)
        val cancellation = CancellationException("request owner retired")
        val request = async(Dispatchers.IO) {
            operation.executeSuspending {
                bodyEntered.countDown()
                check(releaseBody.await(5, TimeUnit.SECONDS))
                currentCoroutineContext().ensureActive()
            }
        }

        assertTrue(bodyEntered.await(5, TimeUnit.SECONDS))
        request.cancel(cancellation)
        assertTrue(connection.disconnectEntered.await(5, TimeUnit.SECONDS))
        val terminal = assertFailsWith<CancellationException> {
            withTimeout(5_000) { request.await() }
        }

        // kotlinx.coroutines 可能通过在 await 边界复制 CancellationException 来恢复堆栈；
        // 语义终局必须仍然是取消，而不是 Network/IO。
        assertEquals(cancellation.message, terminal.message)
        assertEquals(1, connection.disconnectCalls.get())
        gate.close()
        assertEquals(1, connection.disconnectCalls.get(), "close must reuse the cancellation disconnect")
    }

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
        val terminalFailure = assertIs<HttpConnectionCloseException>(firstClose.get().exceptionOrNull())
        assertSame(terminalFailure, secondClose.get().exceptionOrNull())
        assertEquals(1, first.disconnectCalls.get())
        assertEquals(1, second.disconnectCalls.get(), "later connections must still be disconnected")
        assertSame(terminalFailure, assertFailsWith<HttpConnectionCloseException> { gate.close() })
    }

    @Test
    fun `fatal disconnect drains every connection and is replayed to every closer`() {
        val gate = HttpConnectionOperationGate("test transport")
        val fatalFailure = FatalDisconnectError("fatal disconnect")
        val laterDisconnectFailure = IOException("later disconnect failed")
        val firstAdmissionFailure = IOException("first operation closed")
        val secondAdmissionFailure = IOException("second operation closed")
        val first = TrackingConnection(disconnectFailure = fatalFailure)
        val second = TrackingConnection(disconnectFailure = laterDisconnectFailure)
        val firstOperation = gate.register(first) { firstAdmissionFailure }
        val secondOperation = gate.register(second) { secondAdmissionFailure }
        val firstClose = AtomicReference<Result<Unit>>()
        val secondClose = AtomicReference<Result<Unit>>()

        val leader = thread(name = "http-gate-fatal-close-leader") {
            firstClose.set(runCatching(gate::close))
        }
        assertTrue(first.disconnectEntered.await(5, TimeUnit.SECONDS))
        assertTrue(second.disconnectEntered.await(5, TimeUnit.SECONDS))
        val follower = thread(name = "http-gate-fatal-close-follower") {
            secondClose.set(runCatching(gate::close))
        }

        assertSame(fatalFailure, assertFailsWith<FatalDisconnectError> { firstOperation.execute {} })
        assertSame(secondAdmissionFailure, assertFailsWith<IOException> { secondOperation.execute {} })
        leader.join(5_000)
        follower.join(5_000)

        assertFalse(leader.isAlive)
        assertFalse(follower.isAlive)
        assertEquals(1, first.disconnectCalls.get())
        assertEquals(1, second.disconnectCalls.get(), "a fatal failure must not skip later disconnects")
        assertSame(fatalFailure, firstClose.get().exceptionOrNull())
        assertSame(fatalFailure, secondClose.get().exceptionOrNull())
        assertSame(fatalFailure, assertFailsWith<FatalDisconnectError> { gate.close() })
        assertTrue(fatalFailure.suppressed.any { it === firstAdmissionFailure })
        assertTrue(fatalFailure.suppressed.any { it === laterDisconnectFailure })
    }

    @Test
    fun `fatal disconnect outranks an ordinary operation failure`() {
        val gate = HttpConnectionOperationGate("test transport")
        val bodyFailure = IOException("request body failed")
        val fatalFailure = FatalDisconnectError("fatal disconnect")
        val operation = gate.register(TrackingConnection(disconnectFailure = fatalFailure)) {
            IOException("closed")
        }

        val executionFailure = assertFailsWith<FatalDisconnectError> {
            operation.execute { throw bodyFailure }
        }

        assertSame(fatalFailure, executionFailure)
        assertTrue(fatalFailure.suppressed.any { it === bodyFailure })
        assertSame(fatalFailure, assertFailsWith<FatalDisconnectError> { gate.close() })
    }

    @Test
    fun `disconnect cancellation is preserved as the close terminal`() {
        val gate = HttpConnectionOperationGate("test transport")
        val bodyFailure = IOException("request body failed")
        val cancellation = CancellationException("disconnect cancelled")
        val operation = gate.register(TrackingConnection(disconnectFailure = cancellation)) {
            IOException("closed")
        }

        val executionFailure = assertFailsWith<CancellationException> {
            operation.execute { throw bodyFailure }
        }

        assertSame(cancellation, executionFailure)
        assertTrue(cancellation.suppressed.any { it === bodyFailure })
        assertSame(cancellation, assertFailsWith<CancellationException> { gate.close() })
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
    fun `fatal disconnect outranks the reentrant close boundary`() {
        val gate = HttpConnectionOperationGate("test transport")
        val fatalFailure = FatalDisconnectError("fatal disconnect")
        val connection = TrackingConnection(disconnectFailure = fatalFailure)
        val operation = gate.register(connection) { IOException("closed") }

        val executionFailure = assertFailsWith<FatalDisconnectError> {
            operation.execute { gate.close() }
        }

        assertSame(fatalFailure, executionFailure)
        assertEquals(1, connection.disconnectCalls.get())
        assertTrue(fatalFailure.suppressed.any { it is HttpConnectionReentrantCloseException })
        assertSame(fatalFailure, assertFailsWith<FatalDisconnectError> { gate.close() })
    }

    @Test
    fun `fatal sibling disconnect outranks a reentrant close before the graph drains`() {
        val gate = HttpConnectionOperationGate("test transport")
        val fatalFailure = FatalDisconnectError("fatal sibling disconnect")
        val currentOperation = gate.register(TrackingConnection()) { IOException("current closed") }
        val siblingConnection = TrackingConnection(disconnectFailure = fatalFailure)
        val siblingOperation = gate.register(siblingConnection) { IOException("sibling closed") }
        val immediateBoundary = AtomicReference<HttpConnectionReentrantCloseException>()

        val reentrantResult = assertFailsWith<FatalDisconnectError> {
            currentOperation.execute {
                val boundary = assertFailsWith<HttpConnectionReentrantCloseException> { gate.close() }
                immediateBoundary.set(boundary)
                throw boundary
            }
        }

        assertSame(fatalFailure, reentrantResult)
        assertSame(fatalFailure, immediateBoundary.get().knownFatalFailure)
        assertTrue(immediateBoundary.get().suppressed.any { it === fatalFailure })
        assertSame(fatalFailure, assertFailsWith<FatalDisconnectError> { siblingOperation.execute {} })
        assertEquals(1, siblingConnection.disconnectCalls.get())
        assertSame(fatalFailure, assertFailsWith<FatalDisconnectError> { gate.close() })
    }

    @Test
    fun `known fatal close failure outranks a reentrant follower boundary`() {
        val gate = HttpConnectionOperationGate("test transport")
        val fatalFailure = FatalDisconnectError("fatal sibling disconnect")
        val operationEntered = CountDownLatch(1)
        val allowReentrantClose = CountDownLatch(1)
        val releaseSweep = CountDownLatch(1)
        val currentOperation = gate.register(TrackingConnection()) { IOException("current closed") }
        val fatalConnection = TrackingConnection(disconnectFailure = fatalFailure)
        val fatalOperation = gate.register(fatalConnection) { IOException("fatal sibling closed") }
        val sweepBlocker = TrackingConnection(disconnectRelease = releaseSweep)
        val blockerAdmissionFailure = IOException("sweep blocker closed")
        val blockerOperation = gate.register(sweepBlocker) { blockerAdmissionFailure }
        val operationResult = AtomicReference<Result<Unit>>()
        val immediateBoundary = AtomicReference<HttpConnectionReentrantCloseException>()
        val closeResult = AtomicReference<Result<Unit>>()

        val operationThread = thread(name = "http-gate-reentrant-operation") {
            operationResult.set(runCatching {
                currentOperation.execute {
                    operationEntered.countDown()
                    check(allowReentrantClose.await(5, TimeUnit.SECONDS))
                    val boundary = assertFailsWith<HttpConnectionReentrantCloseException> { gate.close() }
                    immediateBoundary.set(boundary)
                    throw boundary
                }
            })
        }
        assertTrue(operationEntered.await(5, TimeUnit.SECONDS))
        val closeThread = thread(name = "http-gate-external-close") {
            closeResult.set(runCatching(gate::close))
        }
        assertTrue(fatalConnection.disconnectEntered.await(5, TimeUnit.SECONDS))
        assertTrue(sweepBlocker.disconnectEntered.await(5, TimeUnit.SECONDS))

        allowReentrantClose.countDown()
        operationThread.join(5_000)
        assertFalse(operationThread.isAlive)
        assertSame(fatalFailure, operationResult.get().exceptionOrNull())
        assertSame(fatalFailure, immediateBoundary.get().knownFatalFailure)
        assertTrue(immediateBoundary.get().suppressed.any { it === fatalFailure })

        releaseSweep.countDown()
        assertSame(fatalFailure, assertFailsWith<FatalDisconnectError> { fatalOperation.execute {} })
        assertSame(blockerAdmissionFailure, assertFailsWith<IOException> { blockerOperation.execute {} })
        closeThread.join(5_000)
        assertFalse(closeThread.isAlive)
        assertSame(fatalFailure, closeResult.get().exceptionOrNull())
        assertSame(fatalFailure, assertFailsWith<FatalDisconnectError> { gate.close() })
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

    @Test
    fun `fatal rejected disconnect outranks the ordinary admission failure`() {
        val gate = HttpConnectionOperationGate("test transport")
        gate.close()
        val admissionFailure = IOException("closed")
        val fatalFailure = FatalDisconnectError("fatal rejected disconnect")
        val connection = TrackingConnection(disconnectFailure = fatalFailure)

        val rejection = assertFailsWith<FatalDisconnectError> {
            gate.register(connection) { admissionFailure }
        }

        assertSame(fatalFailure, rejection)
        assertTrue(fatalFailure.suppressed.any { it === admissionFailure })
        assertEquals(1, connection.disconnectCalls.get())
    }

    @Test
    fun `fatal rejected-disconnect reporter failure is not swallowed`() {
        val gate = HttpConnectionOperationGate("test transport")
        gate.close()
        val admissionFailure = IOException("closed")
        val disconnectFailure = IOException("rejected cleanup failed")
        val reportFailure = CancellationException("report cancelled")
        val connection = TrackingConnection(disconnectFailure = disconnectFailure)

        val rejection = assertFailsWith<CancellationException> {
            gate.register(
                connection = connection,
                onRejectedDisconnectFailure = { throw reportFailure },
                closedFailure = { admissionFailure },
            )
        }

        assertSame(reportFailure, rejection)
        assertTrue(reportFailure.suppressed.any { it === admissionFailure })
        assertTrue(admissionFailure.suppressed.any { it === disconnectFailure })
        assertEquals(1, connection.disconnectCalls.get())
    }

    private class TrackingConnection(
        private val disconnectFailure: Throwable? = null,
        private val disconnectRelease: CountDownLatch? = null,
        private val disconnectAction: () -> Unit = {},
    ) : HttpURLConnection(URL("https://example.test")) {
        val disconnectCalls = AtomicInteger()
        val disconnectEntered = CountDownLatch(1)

        override fun connect() = Unit

        override fun disconnect() {
            disconnectCalls.incrementAndGet()
            disconnectEntered.countDown()
            disconnectAction()
            disconnectRelease?.let { check(it.await(5, TimeUnit.SECONDS)) }
            disconnectFailure?.let { throw it }
        }

        override fun usingProxy(): Boolean = false
    }

    private class FatalDisconnectError(message: String) : Error(message)
}
