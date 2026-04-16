package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.Message
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import io.netty.handler.codec.CorruptedFrameException
import org.slf4j.LoggerFactory

/**
 * 编解码合一的 Codec：ByteBuf ↔ IProto 类型化对象。
 *
 * 入站：ByteBuf → PacketCodec.decode() → IProto 对象（自动反序列化）
 * 出站：channel.writeAndFlush(proto) → PacketCodec.encode() → ByteBuf
 *
 * 零载荷类型（PING/PONG/DISCONNECT）直接产出 Signal 对象。
 * 有载荷类型通过 PacketType 注册表中的 IProtoCreator 自动反序列化。
 */
class PacketCodec : ByteToMessageCodec<IProto>() {

    private val logger = LoggerFactory.getLogger(PacketCodec::class.java)

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        while (buf.readableBytes() >= 5) {
            buf.markReaderIndex()
            val typeCode = buf.readByte()
            val length = buf.readInt()

            val packetType = PacketType.fromCode(typeCode)
            if (packetType == null) {
                logger.warn("Unknown PacketType code: {}, closing connection", typeCode)
                throw CorruptedFrameException("Unknown packet type: $typeCode")
            }
            if (length < 0 || length > IProto.MAX_PACKET_SIZE) {
                logger.warn("Invalid payload length: {}, closing connection", length)
                throw CorruptedFrameException("Invalid packet length: $length")
            }

            if (buf.readableBytes() < length) {
                buf.resetReaderIndex()
                return
            }

            // 零载荷 → Signal 对象
            if (length == 0) {
                val signal = when (packetType) {
                    PacketType.PING -> com.virjar.tk.protocol.payload.PingSignal
                    PacketType.PONG -> com.virjar.tk.protocol.payload.PongSignal
                    PacketType.DISCONNECT -> com.virjar.tk.protocol.payload.DisconnectSignal
                    else -> {
                        // 其他零载荷类型，尝试从 creator 获取
                        val creator = PacketType.creatorFor<IProto>(packetType)
                        if (creator != null) {
                            out.add(creator.create(buf))
                            continue
                        }
                        logger.warn("Zero-length payload for type {} with no creator, closing connection", packetType)
                        throw CorruptedFrameException("Unknown zero-length type: $packetType")
                    }
                }
                out.add(signal)
                continue
            }

            // 有载荷 → 消息类型走 Message.readFrom，非消息类型走 IProtoCreator
            if (PacketType.isMessageType(packetType)) {
                val payloadBuf = buf.retainedSlice(buf.readerIndex(), length)
                buf.skipBytes(length)
                try {
                    out.add(Message.readFrom(payloadBuf, packetType))
                } finally {
                    payloadBuf.release()
                }
            } else {
                val creator = PacketType.creatorFor<IProto>(packetType)
                if (creator != null) {
                    val payloadBuf = buf.retainedSlice(buf.readerIndex(), length)
                    buf.skipBytes(length)
                    try {
                        out.add(creator.create(payloadBuf))
                    } finally {
                        payloadBuf.release()
                    }
                } else {
                    // 未知载荷类型 → 协议不对齐，视为异常连接
                    logger.warn("No creator for PacketType {}, closing connection", packetType)
                    throw CorruptedFrameException("Unknown payload type: $packetType")
                }
            }
        }
    }

    override fun encode(ctx: ChannelHandlerContext, msg: IProto, out: ByteBuf) {
        val type = PacketType.typeFor(msg)
        out.writeByte(type.code.toInt())
        // 先写入 payload 再回填实际长度
        val sizeIndex = out.writerIndex()
        out.writeInt(0) // placeholder
        val startIdx = out.writerIndex()
        msg.writeTo(out)
        val actualSize = out.writerIndex() - startIdx
        out.setInt(sizeIndex, actualSize)
    }
}
