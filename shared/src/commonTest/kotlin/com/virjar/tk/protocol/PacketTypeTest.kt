package com.virjar.tk.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PacketTypeTest {

    @Test
    fun `fromCode returns correct PacketType for all defined codes`() {
        assertEquals(PacketType.AUTH, PacketType.fromCode(1))
        assertEquals(PacketType.AUTH_RESP, PacketType.fromCode(2))
        assertEquals(PacketType.DISCONNECT, PacketType.fromCode(3))
        assertEquals(PacketType.PING, PacketType.fromCode(4))
        assertEquals(PacketType.PONG, PacketType.fromCode(5))
        assertEquals(PacketType.SUBSCRIBE, PacketType.fromCode(10))
        assertEquals(PacketType.UNSUBSCRIBE, PacketType.fromCode(11))
        assertEquals(PacketType.HISTORY_LOAD, PacketType.fromCode(12))
        assertEquals(PacketType.HISTORY_LOAD_END, PacketType.fromCode(13))
        assertEquals(PacketType.TEXT, PacketType.fromCode(20))
        assertEquals(PacketType.IMAGE, PacketType.fromCode(21))
        assertEquals(PacketType.VOICE, PacketType.fromCode(22))
        assertEquals(PacketType.VIDEO, PacketType.fromCode(23))
        assertEquals(PacketType.FILE, PacketType.fromCode(24))
        assertEquals(PacketType.LOCATION, PacketType.fromCode(25))
        assertEquals(PacketType.CARD, PacketType.fromCode(26))
        assertEquals(PacketType.REPLY, PacketType.fromCode(27))
        assertEquals(PacketType.FORWARD, PacketType.fromCode(28))
        assertEquals(PacketType.MERGE_FORWARD, PacketType.fromCode(29))
        assertEquals(PacketType.REVOKE, PacketType.fromCode(30))
        assertEquals(PacketType.EDIT, PacketType.fromCode(31))
        assertEquals(PacketType.TYPING, PacketType.fromCode(32))
        assertEquals(PacketType.STICKER, PacketType.fromCode(33))
        assertEquals(PacketType.REACTION, PacketType.fromCode(34))
        assertEquals(PacketType.INTERACTIVE, PacketType.fromCode(35))
        assertEquals(PacketType.RICH, PacketType.fromCode(36))
        assertEquals(PacketType.SENDACK, PacketType.fromCode(80))
        assertEquals(PacketType.RECVACK, PacketType.fromCode(81))
        assertEquals(PacketType.CHANNEL_CREATED, PacketType.fromCode(90))
        assertEquals(PacketType.CMD, PacketType.fromCode(100))
        assertEquals(PacketType.ACK, PacketType.fromCode(101))
        assertEquals(PacketType.PRESENCE, PacketType.fromCode(102))
    }

    @Test
    fun `fromCode returns null for undefined codes`() {
        assertNull(PacketType.fromCode(0))
        assertNull(PacketType.fromCode(50))
        assertNull(PacketType.fromCode(-1))
        assertNull(PacketType.fromCode(127))
    }

    @Test
    fun `all entries have unique codes`() {
        val codes = PacketType.entries.map { it.code }
        assertEquals(PacketType.entries.size, codes.toSet().size)
    }

    @Test
    fun `code property matches expected byte value`() {
        assertEquals(20, PacketType.TEXT.code)
        assertEquals(80, PacketType.SENDACK.code)
        assertEquals(100, PacketType.CMD.code)
    }
}
