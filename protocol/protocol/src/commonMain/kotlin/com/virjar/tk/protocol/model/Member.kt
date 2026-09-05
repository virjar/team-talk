package com.virjar.tk.protocol.model

import kotlinx.serialization.Serializable

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

@Serializable
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
        buf.writeBoolean(user != null)
        user?.writeTo(buf)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<Member> {
        override fun readFrom(buf: PacketBuffer): Member = Member(
            uid = buf.readRequiredString(),
            chatId = buf.readRequiredString(),
            role = buf.readVarInt(),
            nickname = buf.readString(),
            joinedAt = buf.readVarLong(),
            user = if (buf.readBoolean("member user presence")) User.readFrom(buf) else null,
        )
    }
}
