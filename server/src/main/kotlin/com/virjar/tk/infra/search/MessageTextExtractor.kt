package com.virjar.tk.infra.search

import com.virjar.tk.body.*
import com.virjar.tk.model.Message
import com.virjar.tk.model.MessageBody
import com.virjar.tk.protocol.MessageType

/**
 * 从 Message 中提取可搜索文本。
 */
object MessageTextExtractor {

    fun extract(message: Message, body: MessageBody?): String? {
        if (body == null) return null
        return try {
            extractFromBody(message.messageType, body)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFromBody(messageType: Int, body: MessageBody): String? {
        return when (MessageType.fromCode(messageType)) {
            MessageType.TEXT -> (body as? TextBody)?.text
            MessageType.IMAGE -> null
            MessageType.VOICE -> null
            MessageType.VIDEO -> null
            MessageType.FILE -> (body as? FileBody)?.fileName
            MessageType.LOCATION -> {
                val loc = body as? LocationBody ?: return null
                listOfNotNull(loc.title, loc.address).filter { it.isNotEmpty() }
                    .joinToString(" ").ifEmpty { null }
            }
            MessageType.CARD -> (body as? CardBody)?.targetName
            MessageType.REPLY -> (body as? ReplyBody)?.replySnippet
            MessageType.FORWARD -> (body as? ForwardBody)?.forwardNote
            MessageType.MERGE_FORWARD -> (body as? MergeForwardBody)?.title
            MessageType.REVOKE -> null
            MessageType.EDIT -> (body as? EditBody)?.newContent
            MessageType.STICKER -> null
            MessageType.REACTION -> null
            MessageType.TYPING -> null
            MessageType.GENERIC, null -> null
        }
    }
}
