package com.virjar.tk.body

import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

data class CardBody(
    val targetUid: String,
    val targetName: String,
    val targetAvatar: String? = null,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(targetUid)
        buf.writeString(targetName)
        buf.writeString(targetAvatar)
    }

    companion object : IProtoReader<CardBody> {
        override fun readFrom(buf: PacketBuffer) = CardBody(
            targetUid = buf.readString()!!,
            targetName = buf.readString()!!,
            targetAvatar = buf.readString(),
        )
    }
}
