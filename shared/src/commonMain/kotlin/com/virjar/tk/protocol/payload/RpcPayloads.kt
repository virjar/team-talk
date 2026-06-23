package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ServiceId

// ── INVOKE payload ──

data class InvokePayload(
    val requestId: Int,
    val serviceId: ServiceId,
    val methodId: Int,
    val payload: ByteArray?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(requestId)
        buf.writeVarInt(serviceId.id)
        buf.writeVarInt(methodId)
        buf.writeBytes(payload)
    }

    companion object : IProtoReader<InvokePayload> {
        override fun readFrom(buf: PacketBuffer) = InvokePayload(
            requestId = buf.readVarInt(),
            serviceId = ServiceId.fromId(buf.readVarInt()),
            methodId = buf.readVarInt(),
            payload = buf.readBytes(),
        )
    }
}

// ── RESPONSE payload ──

data class ResponsePayload(
    val requestId: Int,
    val status: Int,        // 0=OK, 非0=错误码
    val payload: ByteArray?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(requestId)
        buf.writeVarInt(status)
        buf.writeBytes(payload)
    }

    companion object : IProtoReader<ResponsePayload> {
        override fun readFrom(buf: PacketBuffer) = ResponsePayload(
            requestId = buf.readVarInt(),
            status = buf.readVarInt(),
            payload = buf.readBytes(),
        )
    }
}

// ── STREAM_ITEM payload ──

data class StreamItemPayload(
    val requestId: Int,
    val payload: ByteArray?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(requestId)
        buf.writeBytes(payload)
    }

    companion object : IProtoReader<StreamItemPayload> {
        override fun readFrom(buf: PacketBuffer) = StreamItemPayload(
            requestId = buf.readVarInt(),
            payload = buf.readBytes(),
        )
    }
}

// ── STREAM_END payload ──

data class StreamEndPayload(
    val requestId: Int,
    val status: Int,
    val payload: ByteArray?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(requestId)
        buf.writeVarInt(status)
        buf.writeBytes(payload)
    }

    companion object : IProtoReader<StreamEndPayload> {
        override fun readFrom(buf: PacketBuffer) = StreamEndPayload(
            requestId = buf.readVarInt(),
            status = buf.readVarInt(),
            payload = buf.readBytes(),
        )
    }
}
