package com.virjar.tk.body

import com.virjar.tk.model.Attachment
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer

/**
 * 统一的附件消息契约。
 *
 * 业务层通过此接口遍历/替换附件，不再为 File/Image/Voice/Video/Sticker
 * 维护平行的 when 分支。缩略图也是完整 [Attachment]，不存在第二套路径语义。
 */
sealed interface AttachmentBody : MessageBody {
    val attachmentMessageType: MessageType
    val attachment: Attachment
    val thumbnail: Attachment? get() = null

    fun withAttachments(attachment: Attachment, thumbnail: Attachment?): AttachmentBody
}

internal fun PacketBuffer.writeAttachmentOrNull(attachment: Attachment?) {
    writeByte(if (attachment == null) 0 else 1)
    attachment?.writeTo(this)
}

internal fun PacketBuffer.readAttachmentOrNull(): Attachment? =
    if (!readPresenceFlag("attachment")) null else Attachment.readFrom(this)
