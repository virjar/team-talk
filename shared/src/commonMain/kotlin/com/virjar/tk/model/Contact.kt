package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

data class Contact(
    val uid: String,
    val friendUid: String,
    val remark: String? = null,
    val status: Int = 1,     // 1=normal, 2=blocked
    val user: User? = null,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(uid)
        buf.writeString(friendUid)
        buf.writeString(remark)
        buf.writeVarInt(status)
        buf.writeByte(if (user != null) 1 else 0)
        user?.writeTo(buf)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<Contact> {
        override fun readFrom(buf: PacketBuffer): Contact = Contact(
            uid = buf.readString()!!,
            friendUid = buf.readString()!!,
            remark = buf.readString(),
            status = buf.readVarInt(),
            user = if (buf.readByte() != 0) User.readFrom(buf) else null,
        )
    }
}
