package com.virjar.tk.body

import com.virjar.tk.model.Attachment
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer

data class FileBody(
    override val attachment: Attachment,
) : AttachmentBody {
    override val attachmentMessageType: MessageType = MessageType.FILE

    override fun writeTo(buf: PacketBuffer) {
        attachment.writeTo(buf)
    }

    override fun withAttachments(attachment: Attachment, thumbnail: Attachment?): FileBody =
        copy(attachment = attachment)

    companion object : IProtoReader<FileBody> {
        override fun readFrom(buf: PacketBuffer) = FileBody(
            attachment = Attachment.readFrom(buf),
        )
    }
}
