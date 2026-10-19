package com.virjar.tk.protocol.netty

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketByteSource
import com.virjar.tk.protocol.PacketFrames
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.readPacketFrame
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.TooLongFrameException

/** JVM 调用方保留同一源码名称；wire 角色归属共享协议模块。 */
typealias PacketInboundRole = com.virjar.tk.protocol.PacketInboundRole

/**
 * 共享 `[TYPE(1B)][LENGTH(4B)][PAYLOAD]` 契约的 Netty 流式适配。
 *
 * 帧读取决策（头部校验、payload 解码、AUTH_RESP(CODE_OK) 当帧放宽上限）来自共享协议
 * 模块的 [readPacketFrame]；ByteBuf 的累积由 Netty cumulation 提供，因此这里保持对
 * ByteBuf 的直读，不经过 [com.virjar.tk.protocol.PacketFrameReader] 的内部缓冲。
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
        const val HEADER_SIZE = PacketFrames.HEADER_SIZE
        const val PROTOCOL_VERSION: Int = com.virjar.tk.protocol.ProtocolVersions.CURRENT_ID
        const val MAX_PAYLOAD_SIZE = PacketFrames.MAX_PAYLOAD_SIZE
        const val UNAUTHED_LIMIT = PacketFrames.UNAUTHED_LIMIT
        const val AUTHED_LIMIT = PacketFrames.AUTHED_LIMIT
        const val PING_INTERVAL_SECONDS = PacketFrames.PING_INTERVAL_SECONDS
        const val READ_IDLE_TIMEOUT_SECONDS = PacketFrames.READ_IDLE_TIMEOUT_SECONDS
    }

    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        val frame = try {
            ByteBufFrameSource(input).readPacketFrame(inboundRole, maxPayloadLimit) ?: return
        } catch (failure: ProtocolCorruptionException) {
            throw CorruptedFrameException(failure.message, failure)
        }
        if (frame.widensPayloadLimit) maxPayloadLimit = AUTHED_LIMIT
        out.add(frame.packet)
    }

    override fun encode(ctx: ChannelHandlerContext, message: IProto, output: ByteBuf) {
        try {
            output.writeBytes(PacketFrames.encode(message))
        } catch (failure: ProtocolEncodingException) {
            throw TooLongFrameException(failure.message, failure)
        }
    }

    private class ByteBufFrameSource(private val input: ByteBuf) : PacketByteSource {
        override fun readableBytes(): Int = input.readableBytes()
        override fun markPosition() {
            input.markReaderIndex()
        }

        override fun reset() {
            input.resetReaderIndex()
        }
        override fun readUnsignedByte(): Int = input.readUnsignedByte().toInt()
        override fun readIntBigEndian(): Int = input.readInt()
        override fun readBytes(count: Int): ByteArray = ByteArray(count).also(input::readBytes)
    }
}
