package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.AuthRequestPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthPreambleVersionTest {
    @Test
    fun `AUTH fixed bootstrap marker is validated independently of negotiated version`() {
        val expected = ProtocolLimits.AUTH_PREAMBLE_MARKER.toInt() and 0xFF
        val received = (expected + 1) and 0xFF
        val payload = PacketBuffer().apply {
            writeByte(AuthRequestPayload.PREAMBLE_HIGH)
            writeByte(AuthRequestPayload.PREAMBLE_LOW)
            writeByte(received)
            writeByte(0x01)
        }.toByteArray()

        val failure = assertFailsWith<ProtocolCorruptionException> {
            AuthRequestPayload.readFrom(PacketBuffer(payload))
        }
        assertEquals("Bad auth preamble marker", failure.message)
    }

    @Test
    fun `invalid magic remains generic corruption even when version byte differs`() {
        val payload = PacketBuffer().apply {
            writeByte(0x00)
            writeByte(AuthRequestPayload.PREAMBLE_LOW)
            writeByte((ProtocolLimits.AUTH_PREAMBLE_MARKER.toInt() + 1) and 0xFF)
            writeByte(0x01)
        }.toByteArray()

        val failure = assertFailsWith<ProtocolCorruptionException> {
            AuthRequestPayload.readFrom(PacketBuffer(payload))
        }
        assertTrue(failure.message.orEmpty().startsWith("Bad auth preamble:"))
    }
}
