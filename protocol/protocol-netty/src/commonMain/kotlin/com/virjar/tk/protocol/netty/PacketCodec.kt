package com.virjar.tk.protocol.netty

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketFrames
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.payload.AuthResponsePayload
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.TooLongFrameException

/** JVM 调用方保留同一源码名称；wire 角色归属共享协议模块。 */
typealias PacketInboundRole = com.virjar.tk.protocol.PacketInboundRole

/** 共享 `[TYPE(1B)][LENGTH(4B)][PAYLOAD]` 契约的 Netty 流式适配。 */
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
        const val HEADER_SIZE = PacketFrames.HEADER_SIZE
        const val PROTOCOL_VERSION: Int = com.virjar.tk.protocol.ProtocolVersions.CURRENT_ID
        const val MAX_PAYLOAD_SIZE = PacketFrames.MAX_PAYLOAD_SIZE
        const val UNAUTHED_LIMIT = PacketFrames.UNAUTHED_LIMIT
        const val AUTHED_LIMIT = PacketFrames.AUTHED_LIMIT
        const val PING_INTERVAL_SECONDS = PacketFrames.PING_INTERVAL_SECONDS
        const val READ_IDLE_TIMEOUT_SECONDS = PacketFrames.READ_IDLE_TIMEOUT_SECONDS
    }

    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        if (input.readableBytes() < HEADER_SIZE) return
        input.markReaderIndex()
        val typeCode = input.readUnsignedByte().toInt()
        val length = input.readInt()
        val decoded = try {
            PacketFrames.validateHeader(typeCode, length, inboundRole, maxPayloadLimit)
            if (input.readableBytes() < length) {
                input.resetReaderIndex()
                return
            }
            val payload = ByteArray(length)
            input.readBytes(payload)
            PacketFrames.decode(typeCode, payload, inboundRole, maxPayloadLimit)
        } catch (failure: ProtocolCorruptionException) {
            throw CorruptedFrameException(failure.message, failure)
        }
        // AUTH_RESP 与更大的首个同步帧可能出现在同一次 socket 读中。
        if (inboundRole == PacketInboundRole.CLIENT && decoded is AuthResponsePayload &&
            decoded.code == AuthResponsePayload.CODE_OK
        ) {
            maxPayloadLimit = AUTHED_LIMIT
        }
        out.add(decoded)
    }

    override fun encode(ctx: ChannelHandlerContext, message: IProto, output: ByteBuf) {
        try {
            output.writeBytes(PacketFrames.encode(message))
        } catch (failure: ProtocolEncodingException) {
            throw TooLongFrameException(failure.message, failure)
        }
    }
}
