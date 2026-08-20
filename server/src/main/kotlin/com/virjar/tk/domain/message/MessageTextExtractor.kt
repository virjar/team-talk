package com.virjar.tk.domain.message

import com.virjar.tk.body.*
import com.virjar.tk.model.Message
import com.virjar.tk.body.MessageBody
import com.virjar.tk.protocol.MessageType

/**
 * 从 Message 中提取可搜索文本。
 */
object MessageTextExtractor {

    /** 会话预览列宽（Conversations.lastMessage varchar(500)——超限会整条消息入库失败，曾 code=400） */
    private const val PREVIEW_MAX_CHARS = 400

    fun extract(message: Message, body: MessageBody?): String? {
        if (body == null) return null
        return try {
            extractFromBody(message.messageType, body)?.take(PREVIEW_MAX_CHARS)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFromBody(messageType: Int, body: MessageBody): String? {
        return when (MessageType.fromCode(messageType)) {
            MessageType.RICH_TEXT -> body.plainTextContentOrNull()
            MessageType.INTERACTIVE_CARD -> {
                val card = (body as? InteractiveCardBody)?.toCard()
                listOfNotNull(card?.title, card?.blocks?.filterIsInstance<CardBlock.Text>()?.joinToString(" ") { it.text })
                    .filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
            }
            MessageType.IMAGE -> null
            MessageType.VOICE -> null
            MessageType.VIDEO -> null
            MessageType.FILE -> (body as? FileBody)?.attachment?.name
            MessageType.LOCATION -> {
                val loc = body as? LocationBody ?: return null
                listOfNotNull(loc.title, loc.address).filter { it.isNotEmpty() }
                    .joinToString(" ").ifEmpty { null }
            }
            MessageType.CARD -> (body as? CardBody)?.targetName
            MessageType.REPLY -> (body as? ReplyBody)?.let {
                it.content.takeIf(String::isNotBlank)?.let { markdown -> buildRichTextBody(markdown).plainText }
                    ?: it.replySnippet
            }
            MessageType.FORWARD -> (body as? ForwardBody)?.forwardNote
            MessageType.MERGE_FORWARD -> (body as? MergeForwardBody)?.title
            MessageType.REVOKE -> null
            MessageType.EDIT -> (body as? EditBody)?.newContent
            MessageType.STICKER -> null
            MessageType.REACTION -> null
            // Never index or expose opaque extension bytes. Until a typed extension renderer
            // exists, the conversation projection gets only a stable safe placeholder.
            MessageType.GENERIC -> "不支持的扩展消息"
            MessageType.TYPING, null -> null
        }
    }
}
