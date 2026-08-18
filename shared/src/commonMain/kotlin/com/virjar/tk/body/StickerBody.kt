package com.virjar.tk.body

import com.virjar.tk.model.Attachment
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer

data class StickerBody(
    override val attachment: Attachment,
    val width: Int = 0,
    val height: Int = 0,
) : AttachmentBody {
    override val attachmentMessageType: MessageType = MessageType.STICKER

    override fun writeTo(buf: PacketBuffer) {
        attachment.writeTo(buf)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
    }

    override fun withAttachments(attachment: Attachment, thumbnail: Attachment?): StickerBody =
        copy(attachment = attachment)

    companion object : IProtoReader<StickerBody> {
        override fun readFrom(buf: PacketBuffer) = StickerBody(
            attachment = Attachment.readFrom(buf),
            width = buf.readVarInt(),
            height = buf.readVarInt(),
        )
    }
}
