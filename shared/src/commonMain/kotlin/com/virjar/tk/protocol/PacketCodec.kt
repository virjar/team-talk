package com.virjar.tk.protocol

import com.virjar.tk.model.Message
import com.virjar.tk.protocol.payload.*
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec

/**
 * TCP 帧编解码器。
 *
 * 帧格式：[MAGIC(2B)][VERSION(1B)][TYPE(1B)][LENGTH(4B)][PAYLOAD(LENGTH bytes)]
 *
 * 解码产出 IProto 对象，编码接收 IProto 对象写入 ByteBuf。
 */
class PacketCodec : ByteToMessageCodec<IProto>() {

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        // 至少需要帧头
        if (buf.readableBytes() < Frame.HEADER_SIZE) return

        buf.markReaderIndex()

        val magicHigh = buf.readByte()
        val magicLow = buf.readByte()
        if (magicHigh != Frame.MAGIC_HIGH || magicLow != Frame.MAGIC_LOW) {
            throw io.netty.handler.codec.CorruptedFrameException("Invalid magic bytes")
        }

        val version = buf.readByte()
        if (version != Frame.PROTOCOL_VERSION) {
            throw io.netty.handler.codec.CorruptedFrameException(
                "Unsupported protocol version: $version"
            )
        }

        val typeCode = buf.readByte().toInt() and 0xFF
        val length = buf.readInt()

        if (length < 0 || length > Frame.MAX_PAYLOAD_SIZE) {
            throw io.netty.handler.codec.CorruptedFrameException("Invalid payload length: $length")
        }

        if (buf.readableBytes() < length) {
            buf.resetReaderIndex()
            return
        }

        val packetType = try {
            PacketType.fromCode(typeCode)
        } catch (e: IllegalArgumentException) {
            // 跳过未知类型的 payload（网络边界，可能是恶意数据）
            if (length > 0) buf.skipBytes(length)
            return
        }

        val proto = if (length == 0) {
            // 零载荷信号
            when (packetType) {
                PacketType.PING -> PingSignal
                PacketType.PONG -> PongSignal
                PacketType.DISCONNECT -> DisconnectSignal
                else -> null
            }
        } else {
            val payloadBuf = PacketBuffer(buf.retainedSlice(buf.readerIndex(), length))
            buf.skipBytes(length)
            decodePayload(packetType, payloadBuf)
        }

        if (proto != null) out.add(proto)
    }

    private fun decodePayload(type: PacketType, buf: PacketBuffer): IProto? = when (type) {
        PacketType.AUTH -> AuthRequestPayload.readFrom(buf)
        PacketType.AUTH_RESP -> AuthResponsePayload.readFrom(buf)
        PacketType.INVOKE -> InvokePayload.readFrom(buf)
        PacketType.RESPONSE -> ResponsePayload.readFrom(buf)
        PacketType.STREAM_ITEM -> StreamItemPayload.readFrom(buf)
        PacketType.STREAM_END -> StreamEndPayload.readFrom(buf)
        PacketType.MESSAGE -> Message.readFrom(buf)
        PacketType.MESSAGE_ACK -> MessageAckPayload.readFrom(buf)
        PacketType.NOTIFY -> NotifyPayload.readFrom(buf)
        PacketType.SUBSCRIBE -> SubscribePayload.readFrom(buf)
        PacketType.UNSUBSCRIBE -> UnsubscribePayload.readFrom(buf)
        else -> null
    }

    override fun encode(ctx: ChannelHandlerContext, msg: IProto, out: ByteBuf) {
        val (typeCode, payloadWriter: (PacketBuffer) -> Unit) = resolveTypeAndWriter(msg)

        out.writeByte(Frame.MAGIC_HIGH.toInt())
        out.writeByte(Frame.MAGIC_LOW.toInt())
        out.writeByte(Frame.PROTOCOL_VERSION.toInt())
        out.writeByte(typeCode)

        // 零载荷信号
        if (msg is PingSignal || msg is PongSignal || msg is DisconnectSignal) {
            out.writeInt(0)
            return
        }

        // 先写长度占位
        val lengthIndex = out.writerIndex()
        out.writeInt(0)

        val startIdx = out.writerIndex()
        val buf = PacketBuffer(out)
        payloadWriter(buf)
        val endIdx = out.writerIndex()

        // 回填实际长度
        out.setInt(lengthIndex, endIdx - startIdx)
    }

    private fun resolveTypeAndWriter(msg: IProto): Pair<Int, (PacketBuffer) -> Unit> = when (msg) {
        is AuthRequestPayload -> PacketType.AUTH.code to { it.writePayload(msg) }
        is AuthResponsePayload -> PacketType.AUTH_RESP.code to { it.writePayload(msg) }
        is PingSignal -> PacketType.PING.code to {}
        is PongSignal -> PacketType.PONG.code to {}
        is DisconnectSignal -> PacketType.DISCONNECT.code to {}
        is InvokePayload -> PacketType.INVOKE.code to { it.writePayload(msg) }
        is ResponsePayload -> PacketType.RESPONSE.code to { it.writePayload(msg) }
        is StreamItemPayload -> PacketType.STREAM_ITEM.code to { it.writePayload(msg) }
        is StreamEndPayload -> PacketType.STREAM_END.code to { it.writePayload(msg) }
        is Message -> PacketType.MESSAGE.code to { it.writePayload(msg) }
        is MessageAckPayload -> PacketType.MESSAGE_ACK.code to { it.writePayload(msg) }
        is NotifyPayload -> PacketType.NOTIFY.code to { it.writePayload(msg) }
        is SubscribePayload -> PacketType.SUBSCRIBE.code to { it.writePayload(msg) }
        is UnsubscribePayload -> PacketType.UNSUBSCRIBE.code to { it.writePayload(msg) }
        else -> throw IllegalArgumentException("Unknown proto type: ${msg::class}")
    }

    private fun PacketBuffer.writePayload(msg: IProto) {
        msg.writeTo(this)
    }
}
