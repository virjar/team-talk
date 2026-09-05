package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer

data class VideoBody(
    override val attachment: Attachment,
    val duration: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    override val thumbnail: Attachment? = null,
) : AttachmentBody {
    override val attachmentMessageType: MessageType = MessageType.VIDEO

    override fun writeTo(buf: PacketBuffer) {
        attachment.writeTo(buf)
        buf.writeVarInt(duration)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
        buf.writeAttachmentOrNull(thumbnail)
    }

    override fun withAttachments(attachment: Attachment, thumbnail: Attachment?): VideoBody =
        copy(attachment = attachment, thumbnail = thumbnail)

    companion object : IProtoReader<VideoBody> {
        override fun readFrom(buf: PacketBuffer) = VideoBody(
            attachment = Attachment.readFrom(buf),
            duration = buf.readVarInt(),
            width = buf.readVarInt(),
            height = buf.readVarInt(),
            thumbnail = buf.readAttachmentOrNull(),
        )
    }
}
