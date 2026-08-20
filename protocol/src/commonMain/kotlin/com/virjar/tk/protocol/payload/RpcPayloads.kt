package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

// ── INVOKE payload ──

data class InvokePayload(
    val requestId: Int,
    /** serviceId：@RpcService name 字符串。 */
    val serviceId: String,
    val methodId: Int,
    val payload: ByteArray?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(requestId)
        buf.writeString(serviceId)
        buf.writeVarInt(methodId)
        buf.writeBytes(payload)
    }

    companion object : IProtoReader<InvokePayload> {
        /** 文档正文约一百万字符；4 MiB 可覆盖其 UTF-8 wire，同时阻止 16 MiB 对象排队。 */
        const val MAX_INVOKE_PAYLOAD_BYTES = 4 * 1024 * 1024
        const val MAX_SERVICE_ID_BYTES = 256

        override fun readFrom(buf: PacketBuffer) = InvokePayload(
            requestId = buf.readVarInt(),
            serviceId = buf.readRequiredString(MAX_SERVICE_ID_BYTES),
            methodId = buf.readVarInt(),
            payload = buf.readBytes(MAX_INVOKE_PAYLOAD_BYTES),
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

/**
 * 保留的流式 RPC 数据帧布局。当前只锁定 wire codec，没有 client/server 流状态机，
 * 因而是 reserved / not operational；不得据此声称大列表 RPC 已通过 stream 分片。
 */

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

/**
 * 保留的流式 RPC 终止帧布局。只有同时落地发送端背压、客户端聚合/取消、超时和错误语义后，
 * STREAM_ITEM/STREAM_END 才能成为可用能力。
 */

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
