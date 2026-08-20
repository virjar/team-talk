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
    }

    // ── 写操作 ──

    fun writeByte(value: Int) { buf.writeByte(value) }
    fun writeShort(value: Int) { buf.writeShort(value) }
    fun writeInt(value: Int) { buf.writeInt(value) }
    fun writeLong(value: Long) { buf.writeLong(value) }

    fun writeVarInt(value: Int) {
        require(value >= 0) { "VarInt only supports non-negative values: $value" }
        var v = value
        while (v > 0x7F) {
            buf.writeByte((v and 0x7F) or 0x80)
            v = v shr 7
        }
        buf.writeByte(v)
    }

    fun writeVarLong(value: Long) {
        require(value >= 0) { "VarLong only supports non-negative values: $value" }
        var v = value
        while (v > 0x7F) {
            buf.writeByte((v.toInt() and 0x7F) or 0x80)
            v = v shr 7
        }
        buf.writeByte(v.toInt())
    }

    /** Boolean 的 wire 表示只能是单字节 0/1。 */
    fun writeBoolean(value: Boolean) {
        buf.writeByte(if (value) 1 else 0)
    }

    fun writeString(value: String?) {
        if (value == null) {
            writeBoolean(false)
            return
        }
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        writeBoolean(true)
        writeVarInt(bytes.size)
        buf.writeBytes(bytes)
    }

    fun writeBytes(value: ByteArray?) {
        if (value == null) {
            writeBoolean(false)
            return
        }
        writeBoolean(true)
        writeVarInt(value.size)
        buf.writeBytes(value)
    }

    // ── 读操作 ──

    fun readByte(): Int = buf.readByte().toInt() and 0xFF
    fun readShort(): Int = buf.readShort().toInt()
    fun readInt(): Int = buf.readInt()
    fun readLong(): Long = buf.readLong()
    fun readableBytes(): Int = buf.readableBytes()

    /** 独立 payload 必须被 reader 完整消费，不允许尾随未定义字节。 */
    fun requireExhausted(context: String = "payload") {
        if (buf.readableBytes() != 0) {
            corrupted("$context has ${buf.readableBytes()} trailing bytes")
        }
    }

    fun readVarInt(): Int {
        var result = 0
        for (index in 0 until 5) {
            val byte = readByte()
            // 第 5 字节只允许 Int.MAX_VALUE 剩余的 3 个有效位；其余位意味着
            // 溢出、负值或继续读取第 6 字节，均不是本协议的非负 VarInt。
            if (index == 4 && byte and 0xF8 != 0) corrupted("VarInt overflow")
            result = result or ((byte and 0x7F) shl (index * 7))
            if (byte and 0x80 == 0) {
                if (index > 0 && byte and 0x7F == 0) corrupted("Non-canonical VarInt")
                return result
            }
        }
        corrupted("VarInt exceeds 5 bytes")
    }

    fun readVarLong(): Long {
        var result = 0L
        // writer 只编码非负 Long，因此最多使用 9 个 7-bit 分组（63 bits）。
        for (index in 0 until 9) {
            val byte = readByte()
            result = result or ((byte.toLong() and 0x7F) shl (index * 7))
            if (byte and 0x80 == 0) {
                if (index > 0 && byte and 0x7F == 0) corrupted("Non-canonical VarLong")
                return result
            }
        }
        corrupted("VarLong exceeds 9 bytes")
    }

    fun readString(maxByteLength: Int = MAX_LENGTH_DELIMITED_BYTES): String? {
        if (!readBoolean("String presence")) return null
        val len = readLength(maxByteLength, "String")
        val bytes = ByteArray(len)
        buf.readBytes(bytes)
        return decodeUtf8(bytes, "String")
    }

    /** 必填字符串遇到 null marker 时作为损坏 wire 拒绝，不把错误泄漏成 Kotlin NPE。 */
    fun readRequiredString(
        maxByteLength: Int = MAX_LENGTH_DELIMITED_BYTES,
        fieldName: String = "String",
    ): String {
        if (!readBoolean("$fieldName presence")) corrupted("Missing required $fieldName")
        val len = readLength(maxByteLength, fieldName)
        val bytes = ByteArray(len)
        buf.readBytes(bytes)
        return decodeUtf8(bytes, fieldName)
    }

    fun readBytes(maxLength: Int = MAX_LENGTH_DELIMITED_BYTES): ByteArray? {
        if (!readBoolean("bytes presence")) return null
        val len = readLength(maxLength, "bytes")
        val bytes = ByteArray(len)
        buf.readBytes(bytes)
        return bytes
    }

    /** Boolean 与复合字段 presence marker 都严格只接受 0/1。 */
    fun readBoolean(fieldName: String = "Boolean"): Boolean {
        val value = readByte()
        if (value != 0 && value != 1) corrupted("Invalid $fieldName boolean value: $value")
        return value == 1
    }

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

    private fun decodeUtf8(bytes: ByteArray, fieldName: String): String {
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: kotlin.text.CharacterCodingException) {
            corrupted("Invalid UTF-8 in $fieldName")
        }
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
