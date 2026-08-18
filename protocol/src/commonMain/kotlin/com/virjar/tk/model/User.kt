package com.virjar.tk.model

import kotlinx.serialization.Serializable

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

@Serializable
data class User(
    val uid: String,
    val username: String,
    val name: String,
    val avatar: String? = null,
    val phone: String? = null,
    val sex: Int = 0,
    val role: Int = 0,
    val status: Int = 1,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(uid)
        buf.writeString(username)
        buf.writeString(name)
        buf.writeString(avatar)
        buf.writeString(phone)
        buf.writeVarInt(sex)
        buf.writeVarInt(role)
        buf.writeVarInt(status)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<User> {
        override fun readFrom(buf: PacketBuffer): User = User(
            uid = buf.readString()!!,
            username = buf.readString()!!,
            name = buf.readString()!!,
            avatar = buf.readString(),
            phone = buf.readString(),
            sex = buf.readVarInt(),
            role = buf.readVarInt(),
            status = buf.readVarInt(),
        )
    }
}

/** 用户全局身份类型。群内 Member.role 是另一条独立维度。 */
object UserRole {
    const val HUMAN = 0
    const val BOT = 10
    const val SYSTEM = 20
}
