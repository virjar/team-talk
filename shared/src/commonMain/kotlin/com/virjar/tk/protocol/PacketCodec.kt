package com.virjar.tk.protocol

import com.virjar.tk.model.Message
import com.virjar.tk.protocol.payload.*
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec

/**
 * TCP 帧编解码器。
 *
 * 帧格式（v3）：[TYPE(1B)][LENGTH(4B)][PAYLOAD(LENGTH bytes)]
 *
 * 解码产出 IProto 对象，编码接收 IProto 对象写入 ByteBuf。
 */
class PacketCodec(
    /** 帧长度上限（可变：未认证连接收紧为 [UNAUTHED_LIMIT]，认证成功后调至 16MB）。
     * 慢速攻击防御：防止未认证连接声明大 LENGTH 诱发累积缓冲放大。 */
    @Volatile var maxPayloadLimit: Int = UNAUTHED_LIMIT,
) : ByteToMessageCodec<IProto>() {
    companion object {
        /** 帧头大小：TYPE(1B) + LENGTH(4B) */
        const val HEADER_SIZE = 5

        /** 协议版本（连接级不变量；AUTH 序言魔第 3 字节协商） */
        const val PROTOCOL_VERSION: Byte = 4

        /** 单帧 payload 上限（认证后） */
        const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024  // 16MB

        /** 未认证连接的帧上限：AUTH 包远小于此值，认证前无任何合法大帧 */
        const val UNAUTHED_LIMIT = 4 * 1024

        /** 认证后上限 */
        const val AUTHED_LIMIT = MAX_PAYLOAD_SIZE

        /** 客户端发送 PING 间隔（秒） */
        const val PING_INTERVAL_SECONDS: Long = 15

        /** 读空闲超时（秒），3 倍心跳间隔。超时后主动关闭触发重连 */
        const val READ_IDLE_TIMEOUT_SECONDS: Long = PING_INTERVAL_SECONDS * 3
    }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        // 至少需要帧头
        if (buf.readableBytes() < HEADER_SIZE) return

        buf.markReaderIndex()

        val typeCode = buf.readByte().toInt() and 0xFF
        val length = buf.readInt()

        if (length < 0 || length > maxPayloadLimit) {
            throw io.netty.handler.codec.CorruptedFrameException("Invalid payload length: $length (limit=$maxPayloadLimit)")
        }

        if (buf.readableBytes() < length) {
            buf.resetReaderIndex()
            return
        }

        val packetType = try {
            PacketType.fromCode(typeCode)
        } catch (e: IllegalArgumentException) {
            // 帧级类型集随 PROTOCOL_VERSION 固定：未知 TYPE = 错位/污染/跨版本，
            // 断连（v2 及之前带帧头 magic 时曾静默丢帧——掩盖协议异常）
            throw io.netty.handler.codec.CorruptedFrameException("Unknown packet type: $typeCode")
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
