package com.virjar.tk.protocol

/**
 * 二进制读写工具类。
 * 基于 Netty ByteBuf 的包装，提供 VarInt、String、Bytes 的读写方法。
 */
class PacketBuffer(private val buf: io.netty.buffer.ByteBuf) {

    // ── 写操作 ──

    fun writeByte(value: Int) { buf.writeByte(value) }
    fun writeShort(value: Int) { buf.writeShort(value) }
    fun writeInt(value: Int) { buf.writeInt(value) }
    fun writeLong(value: Long) { buf.writeLong(value) }

    fun writeVarInt(value: Int) {
        var v = value
        while (v > 0x7F) {
            buf.writeByte((v and 0x7F) or 0x80)
            v = v shr 7
        }
        buf.writeByte(v)
    }

    fun writeVarLong(value: Long) {
        var v = value
        while (v > 0x7F) {
            buf.writeByte((v.toInt() and 0x7F) or 0x80)
            v = v shr 7
        }
        buf.writeByte(v.toInt())
    }

    fun writeString(value: String?) {
        if (value == null) {
            buf.writeByte(0)
            return
        }
        val bytes = value.encodeToByteArray()
        buf.writeByte(1)
        writeVarInt(bytes.size)
        buf.writeBytes(bytes)
    }

    fun writeBytes(value: ByteArray?) {
        if (value == null) {
            buf.writeByte(0)
            return
        }
        buf.writeByte(1)
        writeVarInt(value.size)
        buf.writeBytes(value)
    }

    // ── 通用扩展（Escape Hatch） ──
    // wire format: [hasExtension(1B)] [count VarInt] [key1][val1] [key2][val2] ...
    // 无扩展时只写 1 字节（0），对已固化模型零开销。

    fun writeExtension(extras: Map<String, String>?) {
        if (extras == null || extras.isEmpty()) {
            buf.writeByte(0)
            return
        }
        buf.writeByte(1)
        writeVarInt(extras.size)
        for ((k, v) in extras) {
            writeString(k)
            writeString(v)
        }
    }

    // ── 读操作 ──

    fun readByte(): Int = buf.readByte().toInt() and 0xFF
    fun readShort(): Int = buf.readShort().toInt()
    fun readInt(): Int = buf.readInt()
    fun readLong(): Long = buf.readLong()
    fun readableBytes(): Int = buf.readableBytes()

    fun readVarInt(): Int {
        var result = 0
        var shift = 0
        var byte: Int
        do {
            byte = buf.readByte().toInt() and 0xFF
            result = result or ((byte and 0x7F) shl shift)
            shift += 7
        } while (byte and 0x80 != 0)
        return result
    }

    fun readVarLong(): Long {
        var result = 0L
        var shift = 0
        var byte: Int
        do {
            byte = buf.readByte().toInt() and 0xFF
            result = result or ((byte.toLong() and 0x7F) shl shift)
            shift += 7
        } while (byte and 0x80 != 0)
        return result
    }

    fun readString(): String? {
        val present = buf.readByte().toInt()
        if (present == 0) return null
        val len = readVarInt()
        val bytes = ByteArray(len)
        buf.readBytes(bytes)
        return bytes.decodeToString()
    }

    fun readBytes(): ByteArray? {
        val present = buf.readByte().toInt()
        if (present == 0) return null
        val len = readVarInt()
        val bytes = ByteArray(len)
        buf.readBytes(bytes)
        return bytes
    }

    fun readExtension(): Map<String, String>? {
        val hasExt = buf.readByte().toInt()
        if (hasExt == 0) return null
        val count = readVarInt()
        val map = LinkedHashMap<String, String>(count)
        repeat(count) {
            val k = readString() ?: return null
            val v = readString() ?: return null
            map[k] = v
        }
        return map
    }
}
