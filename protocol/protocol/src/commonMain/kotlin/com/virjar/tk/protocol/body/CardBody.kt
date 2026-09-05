package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.UserAvatarPolicy
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

data class CardBody(
    val targetUid: String,
    val targetName: String,
    /** 仅作展示快照；不会产生附件保留引用。 */
    val targetAvatar: Attachment? = null,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(targetUid)
        buf.writeString(targetName)
        buf.writeBoolean(targetAvatar != null)
        targetAvatar?.let(UserAvatarPolicy::requireCanonical)?.writeTo(buf)
    }

    companion object : IProtoReader<CardBody> {
        override fun readFrom(buf: PacketBuffer) = CardBody(
            targetUid = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            ),
            targetName = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH),
            ),
            targetAvatar = if (buf.readBoolean("contact card avatar presence")) {
                UserAvatarPolicy.readFrom(buf, "contact card avatar")
            } else {
                null
            },
        )
    }
}
