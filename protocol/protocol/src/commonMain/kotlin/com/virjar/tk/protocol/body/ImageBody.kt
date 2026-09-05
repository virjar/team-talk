package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer

data class ImageBody(
    override val attachment: Attachment,
    val width: Int = 0,
    val height: Int = 0,
    override val thumbnail: Attachment? = null,
) : AttachmentBody {
    override val attachmentMessageType: MessageType = MessageType.IMAGE

    override fun writeTo(buf: PacketBuffer) {
        attachment.writeTo(buf)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
        buf.writeAttachmentOrNull(thumbnail)
    }

    override fun withAttachments(attachment: Attachment, thumbnail: Attachment?): ImageBody =
        copy(attachment = attachment, thumbnail = thumbnail)

    companion object : IProtoReader<ImageBody> {
        override fun readFrom(buf: PacketBuffer) = ImageBody(
            attachment = Attachment.readFrom(buf),
            width = buf.readVarInt(),
            height = buf.readVarInt(),
            thumbnail = buf.readAttachmentOrNull(),
        )
    }
}
