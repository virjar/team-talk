package com.virjar.tk.protocol

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IProtoTest {

    // ================================================================
    // String 读写
    // ================================================================

    @Test
    fun `writeString and readString round-trip`() {
        val buf = Unpooled.buffer()
        IProto.writeString(buf, "hello world")
        val result = IProto.readString(buf)
        assertEquals("hello world", result)
        buf.release()
    }

    @Test
    fun `writeString and readString with null`() {
        val buf = Unpooled.buffer()
        IProto.writeString(buf, null)
        val result = IProto.readString(buf)
        assertNull(result)
        buf.release()
    }

    @Test
    fun `writeString and readString with empty string`() {
        val buf = Unpooled.buffer()
        IProto.writeString(buf, "")
        val result = IProto.readString(buf)
        assertEquals("", result)
        buf.release()
    }

    @Test
    fun `writeString and readString with unicode`() {
        val buf = Unpooled.buffer()
        IProto.writeString(buf, "你好世界🌍")
        val result = IProto.readString(buf)
        assertEquals("你好世界🌍", result)
        buf.release()
    }

    @Test
    fun `writeString uses VarInt length prefix`() {
        val buf = Unpooled.buffer()
        IProto.writeString(buf, "abc")
        // VarInt(3) = 1 byte + "abc"(3 bytes) = 4 bytes
        assertEquals(4, buf.readableBytes())
        assertEquals(3L, IProto.readVarInt(buf))
        buf.release()
    }

    @Test
    fun `writeString null uses 0xFF marker`() {
        val buf = Unpooled.buffer()
        IProto.writeString(buf, null)
        assertEquals(1, buf.readableBytes())
        assertEquals(0xFF, buf.readByte().toInt() and 0xFF)
        buf.release()
    }

    // ================================================================
    // StringList 读写
    // ================================================================

    @Test
    fun `writeStringList and readStringList round-trip`() {
        val buf = Unpooled.buffer()
        IProto.writeStringList(buf, listOf("a", "b", "c"))
        val result = IProto.readStringList(buf)
        assertEquals(listOf("a", "b", "c"), result)
        buf.release()
    }

    @Test
    fun `writeStringList and readStringList with empty list`() {
        val buf = Unpooled.buffer()
        IProto.writeStringList(buf, emptyList())
        val result = IProto.readStringList(buf)
        assertTrue(result.isEmpty())
        buf.release()
    }

    // ================================================================
    // VarInt 读写
    // ================================================================

    @Test
    fun `writeVarInt and readVarInt with small value`() {
        val buf = Unpooled.buffer()
        IProto.writeVarInt(buf, 42L)
        val result = IProto.readVarInt(buf)
        assertEquals(42L, result)
        buf.release()
    }

    @Test
    fun `writeVarInt and readVarInt with zero`() {
        val buf = Unpooled.buffer()
        IProto.writeVarInt(buf, 0L)
        val result = IProto.readVarInt(buf)
        assertEquals(0L, result)
        buf.release()
    }

    @Test
    fun `writeVarInt and readVarInt with 127 (single byte boundary)`() {
        val buf = Unpooled.buffer()
        IProto.writeVarInt(buf, 127L)
        assertEquals(1, buf.readableBytes()) // fits in 1 byte
        val result = IProto.readVarInt(buf)
        assertEquals(127L, result)
        buf.release()
    }

    @Test
    fun `writeVarInt and readVarInt with 128 (two bytes boundary)`() {
        val buf = Unpooled.buffer()
        IProto.writeVarInt(buf, 128L)
        assertEquals(2, buf.readableBytes()) // needs 2 bytes
        val result = IProto.readVarInt(buf)
        assertEquals(128L, result)
        buf.release()
    }

    @Test
    fun `writeVarInt and readVarInt with large value`() {
        val buf = Unpooled.buffer()
        IProto.writeVarInt(buf, 1_000_000L)
        val result = IProto.readVarInt(buf)
        assertEquals(1_000_000L, result)
        buf.release()
    }

    @Test
    fun `writeVarInt and readVarInt with max safe value`() {
        val buf = Unpooled.buffer()
        val value = (1L shl 50) - 1 // large but safe
        IProto.writeVarInt(buf, value)
        val result = IProto.readVarInt(buf)
        assertEquals(value, result)
        buf.release()
    }

    // ================================================================
    // ByteArray 读写
    // ================================================================

    @Test
    fun `writeByteArray and readByteArray round-trip`() {
        val buf = Unpooled.buffer()
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        IProto.writeByteArray(buf, data)
        val result = IProto.readByteArray(buf)
        assertTrue(data.contentEquals(result!!))
        buf.release()
    }

    @Test
    fun `writeByteArray and readByteArray with null`() {
        val buf = Unpooled.buffer()
        IProto.writeByteArray(buf, null)
        val result = IProto.readByteArray(buf)
        assertNull(result)
        buf.release()
    }

    @Test
    fun `writeByteArray and readByteArray with empty array`() {
        val buf = Unpooled.buffer()
        IProto.writeByteArray(buf, byteArrayOf())
        val result = IProto.readByteArray(buf)
        assertTrue(result!!.isEmpty())
        buf.release()
    }

    @Test
    fun `writeByteArray uses 4-byte length prefix`() {
        val buf = Unpooled.buffer()
        IProto.writeByteArray(buf, byteArrayOf(0x01, 0x02))
        // int(4 bytes) + 2 bytes = 6
        assertEquals(6, buf.readableBytes())
        assertEquals(2, buf.readInt())
        buf.release()
    }
}
