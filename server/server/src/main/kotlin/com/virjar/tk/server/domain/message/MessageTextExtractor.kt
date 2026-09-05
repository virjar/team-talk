package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.body.*
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType

/** 从 Message 中提取完整检索正文，并独立派生有界会话预览。 */
object MessageTextExtractor {

    /** 会话预览列宽（Conversations.lastMessage varchar(500)——超限会整条消息入库失败，曾 code=400） */
    private const val PREVIEW_MAX_CHARS = 400

    fun extractSearchText(message: Message, body: MessageBody?): String? {
        if (body == null) return null
        return try {
            extractFromBody(message.messageType, body)
        } catch (_: Exception) {
            null
        }
    }

    fun extractConversationPreview(message: Message, body: MessageBody?): String? =
        toConversationPreview(extractSearchText(message, body))

    fun toConversationPreview(searchText: String?): String? = searchText?.take(PREVIEW_MAX_CHARS)

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
            MessageType.REPLY -> (body as? ReplyBody)?.let { reply ->
                reply.content.takeIf(String::isNotBlank)
                    ?.let { markdown -> buildRichTextBody(markdown, reply.assets).plainText }
                    ?: reply.replySnippet
            }
            MessageType.FORWARD -> (body as? ForwardBody)?.forwardNote
            MessageType.MERGE_FORWARD -> (body as? MergeForwardBody)?.title
            MessageType.REVOKE -> null
            MessageType.EDIT -> (body as? EditBody)?.newContent
            MessageType.STICKER -> null
            MessageType.REACTION -> null
            // 引用消息的会话预览/搜索文本只暴露冻结的安全快照标题，不承载权威内容。
            MessageType.OFFICE_REF -> (body as? com.virjar.tk.protocol.body.OfficeRefBody)?.let { ref ->
                (if (ref.isDocument) "[文档] " else "[群文件] ") + ref.title
            }
            MessageType.TYPING, null -> null
        }
    }
}
