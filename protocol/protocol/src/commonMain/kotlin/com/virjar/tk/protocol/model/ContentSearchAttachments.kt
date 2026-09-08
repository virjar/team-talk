package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.AttachmentBody
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RichTextBody

/** 服务端索引与客户端打开结果共用的主附件范围；缩略图和表情不成为独立搜索命中。 */
object ContentSearchAttachments {
    fun attachments(message: Message): List<Attachment> {
        if (message.flags and Message.FLAG_REVOKED != 0) return emptyList()
        val attachments = when (val body = message.body) {
            is AttachmentBody -> if (message.messageType in attachmentTypes) listOf(body.attachment) else emptyList()
            is RichTextBody -> body.assets.map { it.attachment }
            is ReplyBody -> body.assets.map { it.attachment }
            else -> emptyList()
        }
        return attachments.map(AttachmentPolicy::canonicalizeDescriptor).distinctBy(Attachment::path)
    }

    /** targetId 为此 canonical path 的 UTF-8 字节的 SHA-256 小写十六进制表示。 */
    fun canonicalPath(attachment: Attachment): String = AttachmentPolicy.canonicalPath(attachment.path)

    fun fileType(attachment: Attachment): Int {
        val mimeType = attachment.contentType.substringBefore(';').trim().lowercase()
        return when {
            mimeType.startsWith("image/") -> ContentSearchRequest.FILE_TYPE_IMAGE
            mimeType.startsWith("video/") -> ContentSearchRequest.FILE_TYPE_VIDEO
            mimeType.startsWith("audio/") -> ContentSearchRequest.FILE_TYPE_AUDIO
            else -> ContentSearchRequest.FILE_TYPE_OTHER
        }
    }

    private val attachmentTypes = setOf(
        MessageType.FILE.code, MessageType.IMAGE.code, MessageType.VOICE.code, MessageType.VIDEO.code,
    )
}
