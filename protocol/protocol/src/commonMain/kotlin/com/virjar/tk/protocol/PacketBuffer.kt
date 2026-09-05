package com.virjar.tk.protocol

/**
 * 纯 Kotlin 的 TeamTalk payload 二进制读写器。
 *
 * 有意不暴露任何网络库类型。传输适配器在解码时传入不可变的 payload 字节数组，
 * 或在编码后获取 [toByteArray]。所有原始值都使用网络字节序，
 * 且每个 buffer 都受 [ProtocolLimits.MAX_PAYLOAD_SIZE] 约束。
 */
class PacketBuffer private constructor(
    private var storage: ByteArray,
    private var writerIndex: Int,
) {
    private var readerIndex: Int = 0

    /** 创建一个有界写入器。同一实例在写入值之后可以被读取。 */
    constructor() : this(ByteArray(DEFAULT_CAPACITY), 0)

    /** 包裹一个完整 payload 用于读取（不复制）。调用方不得修改 [payload]。 */
    constructor(payload: ByteArray) : this(payload, payload.size) {
        if (payload.size > ProtocolLimits.MAX_PAYLOAD_SIZE) {
            corrupted("payload length ${payload.size} exceeds limit ${ProtocolLimits.MAX_PAYLOAD_SIZE}")
        }
    }

    companion object {
        /** 任何长度定界字段都必须容纳在单个已认证 payload 内。 */
        const val MAX_LENGTH_DELIMITED_BYTES = ProtocolLimits.MAX_PAYLOAD_SIZE

        /** 通用兜底分配上限；业务模型通常应选择更小的值。 */
        const val MAX_COLLECTION_ENTRIES = 100_000

        private const val DEFAULT_CAPACITY = 256
    }

    // -- 写操作 -------------------------------------------------

    fun writeByte(value: Int) {
        ensureWritable(1)
        storage[writerIndex++] = value.toByte()
    }

    fun writeShort(value: Int) {
        ensureWritable(Short.SIZE_BYTES)
        storage[writerIndex++] = (value ushr 8).toByte()
        storage[writerIndex++] = value.toByte()
    }

    fun writeInt(value: Int) {
        ensureWritable(Int.SIZE_BYTES)
        for (shift in 24 downTo 0 step 8) storage[writerIndex++] = (value ushr shift).toByte()
    }

    fun writeLong(value: Long) {
        ensureWritable(Long.SIZE_BYTES)
        for (shift in 56 downTo 0 step 8) storage[writerIndex++] = (value ushr shift).toByte()
    }

    fun writeVarInt(value: Int) {
        require(value >= 0) { "VarInt only supports non-negative values: $value" }
        var remaining = value
        while (remaining > 0x7F) {
            writeByte((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        writeByte(remaining)
    }

    fun writeVarLong(value: Long) {
        require(value >= 0) { "VarLong only supports non-negative values: $value" }
        var remaining = value
        while (remaining > 0x7F) {
            writeByte((remaining.toInt() and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        writeByte(remaining.toInt())
    }

    /** Boolean 只有一种 canonical 字节表示：0 或 1。 */
    fun writeBoolean(value: Boolean) = writeByte(if (value) 1 else 0)

    fun writeString(value: String?) {
        if (value == null) {
            writeBoolean(false)
            return
        }
        // 在修改本写入器之前先编码，这样本地非法的 UTF-16 不会留下残缺字段。
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        requireLengthDelimitedWrite(bytes.size, "String")
        writeBoolean(true)
        writeVarInt(bytes.size)
        writeRawBytes(bytes)
    }

    fun writeBytes(value: ByteArray?) {
        if (value == null) {
            writeBoolean(false)
            return
        }
        requireLengthDelimitedWrite(value.size, "bytes")
        writeBoolean(true)
        writeVarInt(value.size)
        writeRawBytes(value)
    }

    /** 快照完整的编码 payload，与当前读取位置无关。 */
    fun toByteArray(): ByteArray = storage.copyOf(writerIndex)

    // -- 读操作 --------------------------------------------------

    fun readByte(): Int {
        requireReadable(1)
        return storage[readerIndex++].toInt() and 0xFF
    }

    fun readShort(): Int {
        val unsigned = (readByte() shl 8) or readByte()
        return if (unsigned and 0x8000 == 0) unsigned else unsigned - 0x1_0000
    }

    fun readInt(): Int =
        (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()

    fun readLong(): Long {
        var result = 0L
        repeat(Long.SIZE_BYTES) { result = (result shl 8) or readByte().toLong() }
        return result
    }

    fun readableBytes(): Int = writerIndex - readerIndex

    /** 独立编码的 payload 必须被完全消费。 */
    fun requireExhausted(context: String = "payload") {
        if (readableBytes() != 0) corrupted("$context has ${readableBytes()} trailing bytes")
    }

    fun readVarInt(): Int {
        var result = 0
        for (index in 0 until 5) {
            val byte = readByte()
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
        // 写入器只编码非负 Long 值，因此九个 7-bit 分组就足够了。
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
        return decodeUtf8(readRawBytes(readLength(maxByteLength, "String")), "String")
    }

    fun readRequiredString(
        maxByteLength: Int = MAX_LENGTH_DELIMITED_BYTES,
        fieldName: String = "String",
    ): String {
        if (!readBoolean("$fieldName presence")) corrupted("Missing required $fieldName")
        return decodeUtf8(readRawBytes(readLength(maxByteLength, fieldName)), fieldName)
    }

    fun readBytes(maxLength: Int = MAX_LENGTH_DELIMITED_BYTES): ByteArray? {
        if (!readBoolean("bytes presence")) return null
        return readRawBytes(readLength(maxLength, "bytes"))
    }

    fun readBoolean(fieldName: String = "Boolean"): Boolean {
        val value = readByte()
        if (value != 0 && value != 1) corrupted("Invalid $fieldName boolean value: $value")
        return value == 1
    }

    /** 同时校验业务基数上限与剩余可用的最小字节数。 */
    fun readCollectionSize(
        maximum: Int = MAX_COLLECTION_ENTRIES,
        minimumBytesPerEntry: Int = 0,
        fieldName: String = "collection",
    ): Int {
        require(maximum >= 0 && minimumBytesPerEntry >= 0) { "集合预算不能为负数" }
        val count = readVarInt()
        if (count > maximum) corrupted("$fieldName count $count exceeds limit $maximum")
        if (minimumBytesPerEntry > 0 && count > readableBytes() / minimumBytesPerEntry) {
            corrupted("$fieldName count $count exceeds remaining payload")
        }
        return count
    }

    private fun decodeUtf8(bytes: ByteArray, fieldName: String): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: kotlin.text.CharacterCodingException) {
        corrupted("Invalid UTF-8 in $fieldName")
    }

    private fun readLength(maximum: Int, fieldName: String): Int {
        require(maximum >= 0) { "$fieldName 长度预算不能为负数" }
        val length = readVarInt()
        if (length > maximum || length > readableBytes()) {
            corrupted("$fieldName length $length exceeds limit/remaining payload")
        }
        return length
    }

    private fun readRawBytes(length: Int): ByteArray {
        requireReadable(length)
        val result = storage.copyOfRange(readerIndex, readerIndex + length)
        readerIndex += length
        return result
    }

    private fun writeRawBytes(value: ByteArray) {
        ensureWritable(value.size)
        value.copyInto(storage, destinationOffset = writerIndex)
        writerIndex += value.size
    }

    private fun requireLengthDelimitedWrite(length: Int, fieldName: String) {
        if (length > MAX_LENGTH_DELIMITED_BYTES) {
            throw ProtocolEncodingException(
                "$fieldName length $length exceeds limit $MAX_LENGTH_DELIMITED_BYTES",
            )
        }
    }

    private fun requireReadable(length: Int) {
        if (length < 0 || length > readableBytes()) {
            corrupted("payload ended with ${readableBytes()} bytes remaining; requested $length")
        }
    }

    private fun ensureWritable(length: Int) {
        require(length >= 0) { "write length cannot be negative" }
        val required = writerIndex.toLong() + length.toLong()
        if (required > ProtocolLimits.MAX_PAYLOAD_SIZE) {
            throw ProtocolEncodingException(
                "encoded payload length $required exceeds limit ${ProtocolLimits.MAX_PAYLOAD_SIZE}",
            )
        }
        if (required <= storage.size) return

        var newSize = storage.size.coerceAtLeast(1)
        while (newSize < required.toInt()) {
            newSize = (newSize.toLong() * 2L)
                .coerceAtMost(ProtocolLimits.MAX_PAYLOAD_SIZE.toLong())
                .toInt()
        }
        storage = storage.copyOf(newSize)
    }

    private fun corrupted(message: String): Nothing = throw ProtocolCorruptionException(message)
}
