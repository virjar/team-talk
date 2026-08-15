package com.virjar.tk.protocol

/**
 * 在线状态通知载荷（PRESENCE）：好友上下线广播。
 * 服务端直写不持久化（在线状态无补发价值）；客户端经契约表解码。
 */
data class PresencePayload(
    val uid: String,
    val status: Byte,
    val lastSeenAt: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(uid)
        buf.writeByte(status.toInt())
        buf.writeVarLong(lastSeenAt)
    }

    companion object : IProtoReader<PresencePayload> {
        const val STATUS_OFFLINE: Byte = 0
        const val STATUS_ONLINE: Byte = 1

        override fun readFrom(buf: PacketBuffer) = PresencePayload(
            uid = buf.readString()!!,
            status = buf.readByte().toByte(),
            lastSeenAt = buf.readVarLong(),
        )
    }
}
