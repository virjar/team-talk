package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.InvokePayload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Native and JVM must agree on the bytes before their socket adapters run. */
class PacketFramesTest {
    @Test
    fun releasedSignalAndInvokeBytesStayIdenticalAcrossTransports() {
        assertContentEquals(byteArrayOf(4, 0, 0, 0, 0), PacketFrames.encode(PingSignal))
        assertContentEquals(byteArrayOf(5, 0, 0, 0, 0), PacketFrames.encode(PongSignal))
        assertContentEquals(byteArrayOf(3, 0, 0, 0, 0), PacketFrames.encode(DisconnectSignal))
        val frame = byteArrayOf(10, 0, 0, 0, 6, 1, 1, 1, 97, 2, 0)
        val request = InvokePayload(1, "a", 2, null)
        assertContentEquals(frame, PacketFrames.encode(request))
        assertEquals(request, PacketFrames.decode(10, frame.copyOfRange(5, frame.size), PacketInboundRole.SERVER))
    }

    @Test
    fun invalidHeadersFailBeforeTheTransportAllocatesTheBody() {
        for ((type, length, role) in listOf(
            Triple(255, 0, PacketInboundRole.ANY),
            Triple(4, 1, PacketInboundRole.ANY),
            Triple(10, -1, PacketInboundRole.SERVER),
            Triple(10, PacketFrames.UNAUTHED_LIMIT + 1, PacketInboundRole.SERVER),
            Triple(11, 0, PacketInboundRole.SERVER),
        )) {
            assertFailsWith<ProtocolCorruptionException> { PacketFrames.validateHeader(type, length, role) }
        }
        assertFailsWith<ProtocolCorruptionException> {
            PacketFrames.decode(10, byteArrayOf(1, 1, 1, 97, 2, 0, 99), PacketInboundRole.SERVER)
        }
    }
}
