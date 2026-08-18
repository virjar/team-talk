package com.virjar.tk.body

import com.virjar.tk.model.Attachment
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer

data class VoiceBody(
    override val attachment: Attachment,
    val duration: Int = 0,
) : AttachmentBody {
    override val attachmentMessageType: MessageType = MessageType.VOICE

    override fun writeTo(buf: PacketBuffer) {
        attachment.writeTo(buf)
        buf.writeVarInt(duration)
    }

    override fun withAttachments(attachment: Attachment, thumbnail: Attachment?): VoiceBody =
        copy(attachment = attachment)

    companion object : IProtoReader<VoiceBody> {
        override fun readFrom(buf: PacketBuffer) = VoiceBody(
            attachment = Attachment.readFrom(buf),
            duration = buf.readVarInt(),
        )
    }
}
