package com.virjar.tk.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrganizationChangedPayloadTest {
    @Test
    fun `organization change revision has one bounded canonical binary form`() {
        val payload = OrganizationChangedPayload(Long.MAX_VALUE)
        val encoded = ProtoCodec.encode(payload)

        assertEquals(9, encoded.size)
        assertEquals(payload, ProtoCodec.decode(OrganizationChangedPayload, encoded))
        assertFailsWith<IllegalArgumentException> { OrganizationChangedPayload(0L) }
        assertFailsWith<IllegalArgumentException> { OrganizationChangedPayload(-1L) }
    }

    @Test
    fun `organization change revision keeps its golden varlong encoding`() {
        assertContentEquals(
            byteArrayOf(0xAC.toByte(), 0x02),
            ProtoCodec.encode(OrganizationChangedPayload(300L)),
        )
    }
}
