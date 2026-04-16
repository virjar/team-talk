package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoCreator
import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf

// ============================================================
// 控制消息 Payload（PacketType 100-102）
// ============================================================

/**
 * CMD(100) 服务端命令推送
 * payload 为 JSON 字符串——CMD 是通用服务端推送通道，cmdType 决定 payload 的 schema。
 */
data class CmdPayload(
    val cmdType: String,
    val payload: String,
) : IProto {
    override val packetType = PacketType.CMD
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, cmdType)
        IProto.writeString(buf, payload)
    }

    constructor(buf: ByteBuf) : this(
        cmdType = IProto.readString(buf)!!,
        payload = IProto.readString(buf) ?: "",
    )

    companion object : IProtoCreator<CmdPayload> {
        override fun create(buf: ByteBuf) = CmdPayload(buf)
    }
}

/**
 * ACK(101) 通用确认
 */
data class AckPayload(
    val code: Byte,
    val messageId: String?,
    val reason: String?,
) : IProto {
    override val packetType = PacketType.ACK
    override fun writeTo(buf: ByteBuf) {
        buf.writeByte(code.toInt())
        IProto.writeString(buf, messageId)
        IProto.writeString(buf, reason)
    }

    constructor(buf: ByteBuf) : this(
        code = buf.readByte(),
        messageId = IProto.readString(buf),
        reason = IProto.readString(buf),
    )

    companion object : IProtoCreator<AckPayload> {
        override fun create(buf: ByteBuf) = AckPayload(buf)
    }
}

/**
 * PRESENCE(102) 在线状态推送
 */
data class PresencePayload(
    val uid: String,
    val status: Byte,       // 0=offline, 1=online
    val lastSeenAt: Long,   // 最后在线时间 (epoch ms)，online 时为 0
) : IProto {
    override val packetType = PacketType.PRESENCE
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, uid)
        buf.writeByte(status.toInt())
        IProto.writeVarInt(buf, lastSeenAt)
    }

    constructor(buf: ByteBuf) : this(
        uid = IProto.readString(buf)!!,
        status = buf.readByte(),
        lastSeenAt = IProto.readVarInt(buf),
    )

    companion object : IProtoCreator<PresencePayload> {
        override fun create(buf: ByteBuf) = PresencePayload(buf)
    }
}
