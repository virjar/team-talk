package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.AccountBannedPayload
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
            PacketType.ACCOUNT_BANNED,
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

/** Shared wire framing rules. Transports validate the header before allocating the payload. */
object PacketFrames {
    const val HEADER_SIZE = 5
    const val MAX_PAYLOAD_SIZE = ProtocolLimits.MAX_PAYLOAD_SIZE
    const val UNAUTHED_LIMIT = ProtocolLimits.MAX_UNAUTHENTICATED_PAYLOAD_SIZE
    const val AUTHED_LIMIT = MAX_PAYLOAD_SIZE
    const val PING_INTERVAL_SECONDS: Long = 15
    const val READ_IDLE_TIMEOUT_SECONDS: Long = PING_INTERVAL_SECONDS * 3

    fun validateHeader(
        typeCode: Int,
        length: Int,
        role: PacketInboundRole = PacketInboundRole.ANY,
        maxPayloadLimit: Int = UNAUTHED_LIMIT,
    ): PacketType {
        require(maxPayloadLimit in 0..MAX_PAYLOAD_SIZE) { "Invalid payload limit: $maxPayloadLimit" }
        if (length < 0 || length > maxPayloadLimit) {
            throw ProtocolCorruptionException("Invalid payload length: $length (limit=$maxPayloadLimit)")
        }
        val type = try {
            PacketType.fromCode(typeCode)
        } catch (_: IllegalArgumentException) {
            throw ProtocolCorruptionException("Unknown packet type: $typeCode")
        }
        if (!role.accepts(type)) {
            throw ProtocolCorruptionException("Packet type $type is not valid for $role inbound traffic")
        }
        if (type.toSignalOrNull() != null && length != 0) {
            throw ProtocolCorruptionException("Signal packet $type must have an empty payload, got $length bytes")
        }
        return type
    }

    fun decode(
        typeCode: Int,
        payload: ByteArray,
        role: PacketInboundRole = PacketInboundRole.ANY,
        maxPayloadLimit: Int = AUTHED_LIMIT,
    ): IProto {
        val type = validateHeader(typeCode, payload.size, role, maxPayloadLimit)
        return type.toSignalOrNull() ?: decodePayload(type, PacketBuffer(payload))
    }

    fun encode(message: IProto): ByteArray {
        val type = resolveType(message)
        val payload = if (message.isSignal()) ByteArray(0) else ProtoCodec.encode(message)
        if (payload.size > MAX_PAYLOAD_SIZE) {
            throw ProtocolEncodingException("Encoded payload length ${payload.size} exceeds limit $MAX_PAYLOAD_SIZE")
        }
        return ByteArray(HEADER_SIZE + payload.size).also { frame ->
            frame[0] = type.code.toByte()
            for (index in 0 until 4) frame[index + 1] = (payload.size ushr (24 - index * 8)).toByte()
            payload.copyInto(frame, HEADER_SIZE)
        }
    }

    private fun decodePayload(type: PacketType, buffer: PacketBuffer): IProto {
        val decoded = when (type) {
            PacketType.NEGOTIATE -> ProtocolNegotiateRequestPayload.readFrom(buffer)
            PacketType.NEGOTIATE_RESP -> ProtocolNegotiateResponsePayload.readFrom(buffer)
            PacketType.AUTH -> AuthRequestPayload.readFrom(buffer)
            PacketType.AUTH_RESP -> AuthResponsePayload.readFrom(buffer)
            PacketType.ACCOUNT_BANNED -> AccountBannedPayload.readFrom(buffer)
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
                throw ProtocolCorruptionException("Signal packet $type cannot have a payload")
        }
        buffer.requireExhausted("$type payload")
        return decoded
    }

    private fun resolveType(message: IProto): PacketType = when (message) {
        is ProtocolNegotiateRequestPayload -> PacketType.NEGOTIATE
        is ProtocolNegotiateResponsePayload -> PacketType.NEGOTIATE_RESP
        is AuthRequestPayload -> PacketType.AUTH
        is AuthResponsePayload -> PacketType.AUTH_RESP
        is AccountBannedPayload -> PacketType.ACCOUNT_BANNED
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
