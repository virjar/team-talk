package com.virjar.tk.protocol

/** 与传输层无关的编码辅助函数：用于编码完整的协议值与 RPC payload。 */
object ProtoCodec {
    fun encode(proto: IProto): ByteArray = writeBuffer { proto.writeTo(it) }

    fun encodeList(protos: List<IProto>): ByteArray {
        if (protos.size > PacketBuffer.MAX_COLLECTION_ENTRIES) {
            throw ProtocolEncodingException(
                "proto list count ${protos.size} exceeds limit ${PacketBuffer.MAX_COLLECTION_ENTRIES}",
            )
        }
        return writeBuffer { buffer ->
            buffer.writeVarInt(protos.size)
            protos.forEach { it.writeTo(buffer) }
        }
    }

    fun <T : IProto> decode(reader: IProtoReader<T>, bytes: ByteArray): T =
        withPayload(bytes) { reader.readFrom(this) }

    fun <T : IProto> decodeList(reader: IProtoReader<T>, bytes: ByteArray): List<T> =
        withPayload(bytes) {
            val count = readCollectionSize(minimumBytesPerEntry = 1, fieldName = "proto list")
            List(count) { reader.readFrom(this) }
        }

    inline fun encodePayload(crossinline block: PacketBuffer.() -> Unit): ByteArray =
        writeBuffer { buffer -> buffer.block() }

    inline fun <T> withPayload(payload: ByteArray?, block: PacketBuffer.() -> T): T {
        val buffer = PacketBuffer(payload ?: EMPTY_PAYLOAD)
        val result = buffer.block()
        buffer.requireExhausted()
        return result
    }

    @PublishedApi
    internal inline fun writeBuffer(block: (PacketBuffer) -> Unit): ByteArray {
        val buffer = PacketBuffer()
        block(buffer)
        return buffer.toByteArray()
    }

    @PublishedApi
    internal val EMPTY_PAYLOAD = ByteArray(0)
}
