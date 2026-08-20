package com.virjar.tk.model

import kotlinx.serialization.Serializable

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

@Serializable
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
        buf.writeBoolean(fromUser != null)
        fromUser?.writeTo(buf)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<ContactApply> {
        override fun readFrom(buf: PacketBuffer): ContactApply = ContactApply(
            id = buf.readVarLong(),
            fromUid = buf.readRequiredString(),
            toUid = buf.readRequiredString(),
            token = buf.readString(),
            remark = buf.readString(),
            status = buf.readVarInt(),
            createdAt = buf.readVarLong(),
            fromUser = if (buf.readBoolean("contact apply user presence")) User.readFrom(buf) else null,
        )
    }
}
