package com.virjar.tk.protocol

/**
 * 二进制读写工具类。
 * 基于 Netty ByteBuf 的包装，提供 VarInt、String、Bytes 的读写方法。
 */
class PacketBuffer(private val buf: io.netty.buffer.ByteBuf) {

    companion object {
        /** 任何长度分隔字段都必须能被当前帧完整容纳，且不能超过认证后单帧上限。 */
        const val MAX_LENGTH_DELIMITED_BYTES = PacketCodec.MAX_PAYLOAD_SIZE

        /** 通用集合的最后一道分配上限；业务模型可使用更小的专用上限。 */
        const val MAX_COLLECTION_ENTRIES = 100_000

        const val MAX_EXTENSION_ENTRIES = 1_024
    }

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
        for (index in 0 until 5) {
            val byte = readByte()
            // 第 5 字节只允许 Int.MAX_VALUE 剩余的 3 个有效位；其余位意味着
            // 溢出、负值或继续读取第 6 字节，均不是本协议的非负 VarInt。
            if (index == 4 && byte and 0xF8 != 0) corrupted("VarInt overflow")
            result = result or ((byte and 0x7F) shl (index * 7))
            if (byte and 0x80 == 0) return result
        }
        corrupted("VarInt exceeds 5 bytes")
    }

    fun readVarLong(): Long {
        var result = 0L
        // writer 只编码非负 Long，因此最多使用 9 个 7-bit 分组（63 bits）。
        for (index in 0 until 9) {
            val byte = readByte()
            result = result or ((byte.toLong() and 0x7F) shl (index * 7))
            if (byte and 0x80 == 0) return result
        }
        corrupted("VarLong exceeds 9 bytes")
    }

    fun readString(maxByteLength: Int = MAX_LENGTH_DELIMITED_BYTES): String? {
        val present = readPresence("String")
        if (present == 0) return null
        val len = readLength(maxByteLength, "String")
        val bytes = ByteArray(len)
        buf.readBytes(bytes)
        return bytes.decodeToString()
    }

    fun readBytes(maxLength: Int = MAX_LENGTH_DELIMITED_BYTES): ByteArray? {
        val present = readPresence("bytes")
        if (present == 0) return null
        val len = readLength(maxLength, "bytes")
        val bytes = ByteArray(len)
        buf.readBytes(bytes)
        return bytes
    }

    /** 可选复合字段的 presence marker 也只能是 0/1，其他值说明 wire 已错位或被污染。 */
    fun readPresenceFlag(fieldName: String): Boolean = readPresence(fieldName) == 1

    /** 在分配集合前同时校验业务上限和当前帧可提供的最小字节数。 */
    fun readCollectionSize(
        maximum: Int = MAX_COLLECTION_ENTRIES,
        minimumBytesPerEntry: Int = 0,
        fieldName: String = "collection",
    ): Int {
        require(maximum >= 0 && minimumBytesPerEntry >= 0) { "集合预算不能为负数" }
        val count = readVarInt()
        if (count > maximum) corrupted("$fieldName count $count exceeds limit $maximum")
        if (minimumBytesPerEntry > 0 && count > buf.readableBytes() / minimumBytesPerEntry) {
            corrupted("$fieldName count $count exceeds remaining payload")
        }
        return count
    }

    fun readExtension(): Map<String, String>? {
        val hasExt = readPresence("extension")
        if (hasExt == 0) return null
        // 两个非空 String 的最短 wire 形态各 2 字节（present + zero length）。
        val count = readCollectionSize(MAX_EXTENSION_ENTRIES, 4, "extension")
        val map = LinkedHashMap<String, String>(count)
        repeat(count) {
            val k = readString() ?: return null
            val v = readString() ?: return null
            map[k] = v
        }
        return map
    }

    private fun readPresence(fieldName: String): Int {
        val present = readByte()
        if (present != 0 && present != 1) corrupted("Invalid $fieldName presence marker: $present")
        return present
    }

    private fun readLength(maximum: Int, fieldName: String): Int {
        require(maximum >= 0) { "$fieldName 长度预算不能为负数" }
        val length = readVarInt()
        if (length > maximum || length > buf.readableBytes()) {
            corrupted("$fieldName length $length exceeds limit/remaining payload")
        }
        return length
    }

    private fun corrupted(message: String): Nothing =
        throw io.netty.handler.codec.CorruptedFrameException(message)
}
