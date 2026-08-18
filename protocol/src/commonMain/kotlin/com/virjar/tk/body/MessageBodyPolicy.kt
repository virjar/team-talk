package com.virjar.tk.body

import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType

/**
 * 消息类型与消息体的一致性契约。
 *
 * SDK 发送前和服务端落库前共用：禁止 `messageType` 与 body 错配；Markdown 消息的
 * mentions/plainText 是派生数据，必须从 markdown 权威源重建，不能信任调用方上传值。
 */
object MessageBodyPolicy {

    fun canonicalize(message: Message): Message {
        val body = requireNotNull(message.body) { "消息体不能为空" }
        val expectedType = typeOf(body)
        require(message.messageType == expectedType.code) {
            "消息类型与消息体不匹配: body=${body::class.simpleName}, messageType=${message.messageType}"
        }
        val canonicalBody = when (body) {
            is RichTextBody -> buildRichTextBody(body.markdown)
            else -> body
        }
        return if (canonicalBody == body) message else message.copy(body = canonicalBody)
    }

    @Suppress("DEPRECATION")
    fun typeOf(body: MessageBody): MessageType = when (body) {
        is TextBody -> MessageType.TEXT
        is RichTextBody -> MessageType.RICH_TEXT
        is InteractiveCardBody -> MessageType.INTERACTIVE_CARD
        is ImageBody -> MessageType.IMAGE
        is VoiceBody -> MessageType.VOICE
        is VideoBody -> MessageType.VIDEO
        is FileBody -> MessageType.FILE
        is LocationBody -> MessageType.LOCATION
        is CardBody -> MessageType.CARD
        is ReplyBody -> MessageType.REPLY
        is ForwardBody -> MessageType.FORWARD
        is MergeForwardBody -> MessageType.MERGE_FORWARD
        is RevokeBody -> MessageType.REVOKE
        is EditBody -> MessageType.EDIT
        is StickerBody -> MessageType.STICKER
        is ReactionBody -> MessageType.REACTION
    }
}

/** Markdown 源文本；旧 TextBody 只在兼容读取时进入这里。 */
@Suppress("DEPRECATION")
fun MessageBody?.markdownContentOrNull(): String? = when (this) {
    is RichTextBody -> markdown
    is TextBody -> text
    is ReplyBody -> content
    is EditBody -> newContent
    else -> null
}

/** 是否为可直接在 Markdown 编辑器中重新编辑的独立文本消息。 */
@Suppress("DEPRECATION")
fun MessageBody?.isMarkdownTextBody(): Boolean = this is RichTextBody || this is TextBody

/** 去除 Markdown 语法后的可检索/可预览文本。 */
@Suppress("DEPRECATION")
fun MessageBody?.plainTextContentOrNull(): String? = when (this) {
    is RichTextBody -> plainText
    is TextBody -> text
    is ReplyBody -> content.takeIf { it.isNotBlank() }?.let { buildRichTextBody(it).plainText }
    is EditBody -> buildRichTextBody(newContent).plainText
    else -> null
}
