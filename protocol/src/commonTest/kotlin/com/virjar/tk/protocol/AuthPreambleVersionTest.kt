package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.AuthRequestPayload
import io.netty.buffer.Unpooled
import io.netty.handler.codec.CorruptedFrameException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AuthPreambleVersionTest {
    @Test
    fun `valid TeamTalk preamble with a different version is classified precisely`() {
        val expected = PacketCodec.PROTOCOL_VERSION.toInt() and 0xFF
        val received = (expected + 1) and 0xFF
        val byteBuf = Unpooled.buffer().apply {
            writeByte(AuthRequestPayload.PREAMBLE_HIGH)
            writeByte(AuthRequestPayload.PREAMBLE_LOW)
            writeByte(received)
            writeByte(0x01)
        }

        try {
            val failure = assertFailsWith<ProtocolVersionMismatchException> {
                AuthRequestPayload.readFrom(PacketBuffer(byteBuf))
            }
            assertEquals(received, failure.receivedVersion)
            assertEquals(expected, failure.supportedVersion)
        } finally {
            byteBuf.release()
        }
    }

    @Test
    fun `invalid magic remains generic corruption even when version byte differs`() {
        val byteBuf = Unpooled.buffer().apply {
            writeByte(0x00)
            writeByte(AuthRequestPayload.PREAMBLE_LOW)
            writeByte((PacketCodec.PROTOCOL_VERSION.toInt() + 1) and 0xFF)
            writeByte(0x01)
        }

        try {
            val failure = assertFailsWith<CorruptedFrameException> {
                AuthRequestPayload.readFrom(PacketBuffer(byteBuf))
            }
            assertFalse(failure is ProtocolVersionMismatchException)
        } finally {
            byteBuf.release()
        }
    }
}
