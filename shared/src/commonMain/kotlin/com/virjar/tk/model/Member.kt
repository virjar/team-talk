package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

data class Member(
    val uid: String,
    val chatId: String,
    val role: Int,           // 0=member, 1=admin, 2=owner
    val nickname: String? = null,
    val joinedAt: Long = 0,
    val user: User? = null,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(uid)
        buf.writeString(chatId)
        buf.writeVarInt(role)
        buf.writeString(nickname)
        buf.writeVarLong(joinedAt)
        buf.writeByte(if (user != null) 1 else 0)
        user?.writeTo(buf)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<Member> {
        override fun readFrom(buf: PacketBuffer): Member = Member(
            uid = buf.readString()!!,
            chatId = buf.readString()!!,
            role = buf.readVarInt(),
            nickname = buf.readString(),
            joinedAt = buf.readVarLong(),
            user = if (buf.readByte() != 0) User.readFrom(buf) else null,
        )
    }
}
