@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.virjar.tk.shared.client

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketFrames
import com.virjar.tk.protocol.PingSignal
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.shared.platform.PlatformAtomicLong
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import platform.darwin.inet_addr
import platform.posix.*
import kotlin.test.*

/** Runs on an iOS simulator/device, with only an ephemeral 127.0.0.1 listener. */
class IosTcpChannelIntegrationTest {
    @Test
    fun fragmentedAndCoalescedFramesStopAtTheExactChannelCloseBoundary(): Unit = runBlocking {
        val server = BsdLoopbackServer()
        val executor = IosSerialExecutor("teamtalk.test.tcp")
        val ready = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Throwable?>()
        val packets = Channel<IProto>(8)
        val delivered = PlatformAtomicLong(0)
        val closeCount = PlatformAtomicLong(0)
        var peer: BsdLoopbackPeer? = null
        var phase = "starting the connection"
        val channel = IosTcpChannel(
            executor = executor,
            host = "127.0.0.1",
            port = server.port,
            certificatePem = null,
            onReady = { source ->
                // This fixture exercises post-authentication frames, including payloads above 4 KiB.
                source.authenticated = true
                ready.complete(Unit)
            },
            onPacket = { source, packet ->
                delivered.incrementAndGet()
                check(packets.trySend(packet).isSuccess)
                // The fourth complete frame is already in the same peer write as this third one.
                if ((packet as? ResponsePayload)?.requestId == 3) source.close()
            },
            onClosed = { _, failure -> closeCount.incrementAndGet(); closed.complete(failure) },
            onWriteIdle = { error("The fixture must finish before the heartbeat interval") },
        )
        try {
            withTimeout(10_000) {
                withContext(executor) { channel.start() }
                phase = "accepting the connection"
                val accepted = server.acceptPeer().also { peer = it }
                phase = "waiting for the ready callback"
                ready.await()
                val expectedBody = ByteArray(4097) { (it % 251).toByte() }
                val first = PacketFrames.encode(ResponsePayload(1, 0, expectedBody))

                // Keep each incomplete prefix on the real socket before making the next part available.
                accepted.write(first, 0, 2)
                assertNull(withTimeoutOrNull(30) { packets.receive() }, "A partial header cannot be a packet")
                accepted.write(first, 2, PacketFrames.HEADER_SIZE - 2 + 13)
                assertNull(withTimeoutOrNull(30) { packets.receive() }, "A partial body cannot be a packet")
                accepted.write(first, PacketFrames.HEADER_SIZE + 13, first.size - PacketFrames.HEADER_SIZE - 13)
                phase = "receiving the fragmented frame"
                val receivedFirst = assertIs<ResponsePayload>(packets.receive())
                assertEquals(1, receivedFirst.requestId)
                assertEquals(0, receivedFirst.status)
                assertContentEquals(expectedBody, receivedFirst.payload)

                val second = PacketFrames.encode(ResponsePayload(2, 0, byteArrayOf(2)))
                val third = PacketFrames.encode(ResponsePayload(3, 0, byteArrayOf(3)))
                val bufferedLate = PacketFrames.encode(ResponsePayload(4, 0, byteArrayOf(4)))
                accepted.write(second + third + bufferedLate)
                phase = "receiving the coalesced frames"
                for (id in 2..3) {
                    val response = assertIs<ResponsePayload>(packets.receive())
                    assertEquals(id, response.requestId)
                    assertContentEquals(byteArrayOf(id.toByte()), response.payload)
                }
                assertNull(closed.await(), "Closing after a complete frame must not report a transport failure")

                // A post-close write may reach the old socket buffer or be rejected by the kernel.
                // Neither it nor the already buffered fourth frame may reach the retired channel owner.
                accepted.write(PacketFrames.encode(ResponsePayload(5, 0, byteArrayOf(5))), allowDisconnected = true)
                phase = "waiting for socket disconnection"
                accepted.awaitDisconnect()
                assertNull(withTimeoutOrNull(100) { packets.receive() }, "A closed channel delivered a late frame")
                withContext(executor) {
                    assertFalse(channel.isActive)
                    assertFailsWith<IllegalStateException> { channel.writeAndFlush(PingSignal) }
                    channel.close() // Idempotent even after the native cancellation callback has returned.
                }
                assertEquals(3L, delivered.get())
                assertEquals(1L, closeCount.get())
            }
        } catch (failure: TimeoutCancellationException) {
            fail("Loopback timed out while $phase (channel closed: ${closed.isCompleted})", failure)
        } finally {
            withContext(NonCancellable + executor) { channel.close() }
            executor.closeAndDrain()
            peer?.close()
            server.close()
            packets.close()
        }
    }
}

private class BsdLoopbackServer {
    private val descriptor = socket(AF_INET, SOCK_STREAM, 0).also { check(it >= 0) { "Cannot create loopback socket: $errno" } }
    private var closed = false
    val port: Int

    init {
        try {
            makeNonBlocking(descriptor)
            port = memScoped {
                val address = alloc<sockaddr_in>()
                memset(address.ptr, 0, sizeOf<sockaddr_in>().toULong())
                address.sin_len = sizeOf<sockaddr_in>().toUByte()
                address.sin_family = AF_INET.toUByte()
                address.sin_addr.s_addr = inet_addr("127.0.0.1")
                address.sin_port = 0u
                check(bind(descriptor, address.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()) == 0) { "Cannot bind loopback socket: $errno" }
                check(listen(descriptor, 1) == 0) { "Cannot listen on loopback socket: $errno" }
                val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().toUInt() }
                check(getsockname(descriptor, address.ptr.reinterpret(), length.ptr) == 0)
                val networkPort = alloc<UShortVar>().apply { value = address.sin_port }.ptr.reinterpret<UByteVar>()
                (networkPort[0].toInt() shl 8) or networkPort[1].toInt()
            }
        } catch (failure: Throwable) {
            platform.posix.close(descriptor)
            throw failure
        }
    }

    suspend fun acceptPeer(): BsdLoopbackPeer {
        while (true) {
            currentCoroutineContext().ensureActive()
            val accepted = accept(descriptor, null, null)
            if (accepted >= 0) return BsdLoopbackPeer(accepted)
            val failure = errno
            check(failure == EAGAIN || failure == EWOULDBLOCK || failure == EINTR) { "Loopback accept failed: $failure" }
            delay(5)
        }
    }

    fun close() {
        if (!closed) { closed = true; platform.posix.close(descriptor) }
    }
}

private class BsdLoopbackPeer(private val descriptor: Int) {
    private var closed = false
    init {
        try {
            makeNonBlocking(descriptor)
            memScoped {
                val enabled = alloc<IntVar>().apply { value = 1 }
                check(setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, sizeOf<IntVar>().toUInt()) == 0)
            }
        } catch (failure: Throwable) {
            platform.posix.close(descriptor)
            throw failure
        }
    }

    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, allowDisconnected: Boolean = false) {
        var cursor = offset
        while (cursor < offset + length) {
            currentCoroutineContext().ensureActive()
            val count = bytes.usePinned { send(descriptor, it.addressOf(cursor), (offset + length - cursor).toULong(), 0) }
            if (count > 0) { cursor += count.toInt(); continue }
            val failure = errno
            if (allowDisconnected && failure in listOf(EPIPE, ECONNRESET, ENOTCONN)) return
            check(count < 0 && (failure == EAGAIN || failure == EWOULDBLOCK || failure == EINTR)) { "Loopback send failed: $failure" }
            delay(5)
        }
    }

    suspend fun awaitDisconnect() {
        val byte = ByteArray(1)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = byte.usePinned { recv(descriptor, it.addressOf(0), 1u, 0) }
            if (count == 0L) return
            val failure = errno
            if (count < 0 && failure in listOf(ECONNRESET, ENOTCONN)) return
            check(count < 0 && (failure == EAGAIN || failure == EWOULDBLOCK || failure == EINTR)) { "Unexpected client payload or loopback receive failure: $failure" }
            delay(5)
        }
    }

    fun close() {
        if (!closed) { closed = true; platform.posix.close(descriptor) }
    }
}

private fun makeNonBlocking(descriptor: Int) {
    val flags = fcntl(descriptor, F_GETFL)
    check(flags >= 0 && fcntl(descriptor, F_SETFL, flags or O_NONBLOCK) == 0) { "Cannot configure loopback socket: $errno" }
}
