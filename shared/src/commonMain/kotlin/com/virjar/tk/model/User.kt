package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

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
