package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoCreator
import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf

// ============================================================
// 确认消息 Payload（PacketType 80-81）
// ============================================================

/**
 * SENDACK(80) 服务端确认消息发送成功/失败
 */
data class SendAckPayload(
    val messageId: String,
    val clientMsgNo: String,
    val clientSeq: Long,
    val serverSeq: Long,
    val code: Byte,
) : IProto {
    override val packetType = PacketType.SENDACK
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, messageId)
        IProto.writeString(buf, clientMsgNo)
        IProto.writeVarInt(buf, clientSeq)
        IProto.writeVarInt(buf, serverSeq)
        buf.writeByte(code.toInt())
    }

    constructor(buf: ByteBuf) : this(
        messageId = IProto.readString(buf)!!,
        clientMsgNo = IProto.readString(buf)!!,
        clientSeq = IProto.readVarInt(buf),
        serverSeq = IProto.readVarInt(buf),
        code = buf.readByte(),
    )

    companion object : IProtoCreator<SendAckPayload> {
        override fun create(buf: ByteBuf) = SendAckPayload(buf)
    }
}

/**
 * RECVACK(81) 客户端确认收到消息
 */
data class RecvAckPayload(
    val messageId: String,
    val channelId: String,
    val channelType: ChannelType,
    val serverSeq: Long,
) : IProto {
    override val packetType = PacketType.RECVACK
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, messageId)
        IProto.writeString(buf, channelId)
        buf.writeByte(channelType.code)
        IProto.writeVarInt(buf, serverSeq)
    }

    constructor(buf: ByteBuf) : this(
        messageId = IProto.readString(buf)!!,
        channelId = IProto.readString(buf)!!,
        channelType = ChannelType.fromCode(buf.readByte().toInt()),
        serverSeq = IProto.readVarInt(buf),
    )

    companion object : IProtoCreator<RecvAckPayload> {
        override fun create(buf: ByteBuf) = RecvAckPayload(buf)
    }
}
