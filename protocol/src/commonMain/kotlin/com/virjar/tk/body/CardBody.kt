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
            targetUid = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            )!!,
            targetName = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH),
            )!!,
            targetAvatar = buf.readString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_URL_LENGTH)),
        )
    }
}
