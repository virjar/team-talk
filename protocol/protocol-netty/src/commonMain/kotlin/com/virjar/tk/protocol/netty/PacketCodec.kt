package com.virjar.tk.protocol.netty

import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.ProtocolLimits
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PingSignal
import com.virjar.tk.protocol.PongSignal
import com.virjar.tk.protocol.DisconnectSignal
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.ProtocolNegotiateRequestPayload
import com.virjar.tk.protocol.payload.ProtocolNegotiateResponsePayload
import com.virjar.tk.protocol.payload.ConnectionTraceContextPayload
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.payload.StreamEndPayload
import com.virjar.tk.protocol.payload.StreamItemPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import com.virjar.tk.protocol.payload.SyncReadyPayload
import com.virjar.tk.protocol.payload.SyncRequestPayload
import com.virjar.tk.protocol.payload.SyncResetPayload
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.TooLongFrameException

/** 表示此连接上接收并校验入站帧的本地端点。 */
enum class PacketInboundRole {
    ANY,
    SERVER,
    CLIENT;

    internal fun accepts(type: PacketType): Boolean = when (this) {
        ANY -> true
        SERVER -> type in SERVER_INBOUND_TYPES
        CLIENT -> type in CLIENT_INBOUND_TYPES
    }

    private companion object {
        val SERVER_INBOUND_TYPES = setOf(
            PacketType.NEGOTIATE,
            PacketType.AUTH,
            PacketType.SYNC_REQUEST,
            PacketType.DISCONNECT,
            PacketType.PING,
            PacketType.PONG,
            PacketType.INVOKE,
            PacketType.MESSAGE,
        )
        val CLIENT_INBOUND_TYPES = setOf(
            PacketType.NEGOTIATE_RESP,
            PacketType.AUTH_RESP,
            PacketType.SYNC_BATCH,
            PacketType.SYNC_READY,
            PacketType.SYNC_RESET,
            PacketType.DISCONNECT,
            PacketType.PING,
            PacketType.PONG,
            PacketType.RESPONSE,
            PacketType.STREAM_ITEM,
            PacketType.STREAM_END,
            PacketType.MESSAGE,
            PacketType.MESSAGE_ACK,
            PacketType.NOTIFY,
            PacketType.CONNECTION_TRACE_CONTEXT,
        )
    }
}

/**
 * 面向 `[TYPE(1B)][LENGTH(4B)][PAYLOAD]` 帧格式的 Netty 适配器。
 *
 * payload 的解析与写入委托给与传输无关的 :protocol 模块。适配器在两端边界各复制一份有界的
 * payload，因此 Netty 的引用计数对象不会泄漏到契约层或领域层代码中。
 */
class PacketCodec(
    @Volatile var maxPayloadLimit: Int = UNAUTHED_LIMIT,
    private val inboundRole: PacketInboundRole = PacketInboundRole.ANY,
) : ByteToMessageCodec<IProto>() {
    init {
        require(maxPayloadLimit in 0..MAX_PAYLOAD_SIZE) {
            "payload limit must be in 0..$MAX_PAYLOAD_SIZE"
        }
    }

    companion object {
        const val HEADER_SIZE = 5
        const val PROTOCOL_VERSION: Int = com.virjar.tk.protocol.ProtocolVersions.CURRENT_ID
        const val MAX_PAYLOAD_SIZE = ProtocolLimits.MAX_PAYLOAD_SIZE
        const val UNAUTHED_LIMIT = ProtocolLimits.MAX_UNAUTHENTICATED_PAYLOAD_SIZE
        const val AUTHED_LIMIT = MAX_PAYLOAD_SIZE
        const val PING_INTERVAL_SECONDS: Long = 15
        const val READ_IDLE_TIMEOUT_SECONDS: Long = PING_INTERVAL_SECONDS * 3
    }

    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        if (input.readableBytes() < HEADER_SIZE) return

        input.markReaderIndex()
        val typeCode = input.readByte().toInt() and 0xFF
        val length = input.readInt()
        if (length < 0 || length > maxPayloadLimit) {
            throw CorruptedFrameException("Invalid payload length: $length (limit=$maxPayloadLimit)")
        }

        val packetType = try {
            PacketType.fromCode(typeCode)
        } catch (failure: IllegalArgumentException) {
            throw CorruptedFrameException("Unknown packet type: $typeCode", failure)
        }
        if (!inboundRole.accepts(packetType)) {
            throw CorruptedFrameException(
                "Packet type $packetType is not valid for $inboundRole inbound traffic",
            )
        }

        val signal = packetType.toSignalOrNull()
        if (signal != null && length != 0) {
            throw CorruptedFrameException(
                "Signal packet $packetType must have an empty payload, got $length bytes",
            )
        }
        if (input.readableBytes() < length) {
            input.resetReaderIndex()
            return
        }

        val decoded = if (signal != null) {
            signal
        } else {
            val payload = ByteArray(length)
            input.readBytes(payload)
            try {
                decodePayload(packetType, PacketBuffer(payload))
            } catch (failure: ProtocolCorruptionException) {
                throw CorruptedFrameException(failure.message, failure)
            }
        }

        // 成功的 AUTH_RESP 可能与首个较大的同步帧合并出现在同一次读取中。
        if (
            inboundRole == PacketInboundRole.CLIENT &&
            decoded is AuthResponsePayload &&
            decoded.code == AuthResponsePayload.CODE_OK
        ) {
            maxPayloadLimit = AUTHED_LIMIT
        }
        out.add(decoded)
    }

    override fun encode(ctx: ChannelHandlerContext, message: IProto, output: ByteBuf) {
        val packetType = resolveType(message)
        val payload = if (message.isSignal()) ByteArray(0) else try {
            ProtoCodec.encode(message)
        } catch (failure: ProtocolEncodingException) {
            throw TooLongFrameException(failure.message, failure)
        }
        if (payload.size > MAX_PAYLOAD_SIZE) {
            throw TooLongFrameException(
                "Encoded payload length ${payload.size} exceeds limit $MAX_PAYLOAD_SIZE",
            )
        }
        output.writeByte(packetType.code)
        output.writeInt(payload.size)
        output.writeBytes(payload)
    }

    private fun decodePayload(type: PacketType, buffer: PacketBuffer): IProto {
        val decoded = when (type) {
            PacketType.NEGOTIATE -> ProtocolNegotiateRequestPayload.readFrom(buffer)
            PacketType.NEGOTIATE_RESP -> ProtocolNegotiateResponsePayload.readFrom(buffer)
            PacketType.AUTH -> AuthRequestPayload.readFrom(buffer)
            PacketType.AUTH_RESP -> AuthResponsePayload.readFrom(buffer)
            PacketType.SYNC_REQUEST -> SyncRequestPayload.readFrom(buffer)
            PacketType.SYNC_BATCH -> SyncBatchPayload.readFrom(buffer)
            PacketType.SYNC_READY -> SyncReadyPayload.readFrom(buffer)
            PacketType.SYNC_RESET -> SyncResetPayload.readFrom(buffer)
            PacketType.INVOKE -> InvokePayload.readFrom(buffer)
            PacketType.RESPONSE -> ResponsePayload.readFrom(buffer)
            PacketType.STREAM_ITEM -> StreamItemPayload.readFrom(buffer)
            PacketType.STREAM_END -> StreamEndPayload.readFrom(buffer)
            PacketType.MESSAGE -> Message.readFrom(buffer)
            PacketType.MESSAGE_ACK -> MessageAckPayload.readFrom(buffer)
            PacketType.NOTIFY -> NotifyPayload.readFrom(buffer)
            PacketType.CONNECTION_TRACE_CONTEXT -> ConnectionTraceContextPayload.readFrom(buffer)
            PacketType.PING, PacketType.PONG, PacketType.DISCONNECT ->
                throw CorruptedFrameException("Signal packet $type cannot have a payload")
        }
        buffer.requireExhausted("$type payload")
        return decoded
    }

    private fun resolveType(message: IProto): PacketType = when (message) {
        is ProtocolNegotiateRequestPayload -> PacketType.NEGOTIATE
        is ProtocolNegotiateResponsePayload -> PacketType.NEGOTIATE_RESP
        is AuthRequestPayload -> PacketType.AUTH
        is AuthResponsePayload -> PacketType.AUTH_RESP
        is SyncRequestPayload -> PacketType.SYNC_REQUEST
        is SyncBatchPayload -> PacketType.SYNC_BATCH
        is SyncReadyPayload -> PacketType.SYNC_READY
        is SyncResetPayload -> PacketType.SYNC_RESET
        is PingSignal -> PacketType.PING
        is PongSignal -> PacketType.PONG
        is DisconnectSignal -> PacketType.DISCONNECT
        is InvokePayload -> PacketType.INVOKE
        is ResponsePayload -> PacketType.RESPONSE
        is StreamItemPayload -> PacketType.STREAM_ITEM
        is StreamEndPayload -> PacketType.STREAM_END
        is Message -> PacketType.MESSAGE
        is MessageAckPayload -> PacketType.MESSAGE_ACK
        is NotifyPayload -> PacketType.NOTIFY
        is ConnectionTraceContextPayload -> PacketType.CONNECTION_TRACE_CONTEXT
        else -> throw IllegalArgumentException("Unknown proto type: ${message::class}")
    }

    private fun IProto.isSignal(): Boolean =
        this is PingSignal || this is PongSignal || this is DisconnectSignal

    private fun PacketType.toSignalOrNull(): IProto? = when (this) {
        PacketType.PING -> PingSignal
        PacketType.PONG -> PongSignal
        PacketType.DISCONNECT -> DisconnectSignal
        else -> null
    }
}
