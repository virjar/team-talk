package com.virjar.tk.protocol.codec

import com.virjar.tk.protocol.ProtocolVersionMismatchException
import com.virjar.tk.protocol.payload.AuthResponsePayload
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.DecoderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolVersionFailureResponseTest {
    @Test
    fun `wrapped version mismatch receives explicit unsupported response`() {
        val response = protocolVersionFailureResponse(
            DecoderException(ProtocolVersionMismatchException(receivedVersion = 7, supportedVersion = 8)),
        )

        assertEquals(AuthResponsePayload.CODE_VERSION_UNSUPPORTED, response?.code)
        assertEquals("Client protocol 7 is unsupported; server requires 8", response?.reason)
    }

    @Test
    fun `ordinary codec corruption receives no upgrade response`() {
        assertNull(protocolVersionFailureResponse(CorruptedFrameException("bad frame")))
        assertNull(protocolVersionFailureResponse(DecoderException("timeout")))
    }
}
