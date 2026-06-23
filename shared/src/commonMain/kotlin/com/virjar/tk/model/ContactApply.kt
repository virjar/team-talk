package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

data class ContactApply(
    val id: Long,
    val fromUid: String,
    val toUid: String,
    val token: String? = null,
    val remark: String? = null,
    val status: Int = 0,     // 0=pending, 1=accepted, 2=rejected
    val createdAt: Long = 0,
    val fromUser: User? = null,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarLong(id)
        buf.writeString(fromUid)
        buf.writeString(toUid)
        buf.writeString(token)
        buf.writeString(remark)
        buf.writeVarInt(status)
        buf.writeVarLong(createdAt)
        buf.writeByte(if (fromUser != null) 1 else 0)
        fromUser?.writeTo(buf)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<ContactApply> {
        override fun readFrom(buf: PacketBuffer): ContactApply = ContactApply(
            id = buf.readVarLong(),
            fromUid = buf.readString()!!,
            toUid = buf.readString()!!,
            token = buf.readString(),
            remark = buf.readString(),
            status = buf.readVarInt(),
            createdAt = buf.readVarLong(),
            fromUser = if (buf.readByte() != 0) User.readFrom(buf) else null,
        )
    }
}
