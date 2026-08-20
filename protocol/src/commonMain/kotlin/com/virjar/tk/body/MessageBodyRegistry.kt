package com.virjar.tk.body

import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer

/**
 * 消息 Body 编解码注册表。
 * messageType -> Body 的 IProtoReader 映射。
 */
object MessageBodyRegistry {

    private val readers: Map<MessageType, IProtoReader<out MessageBody>> = mapOf(
        MessageType.RICH_TEXT to RichTextBody,
        MessageType.INTERACTIVE_CARD to InteractiveCardBody,
        MessageType.IMAGE to ImageBody,
        MessageType.VOICE to VoiceBody,
        MessageType.VIDEO to VideoBody,
        MessageType.FILE to FileBody,
        MessageType.LOCATION to LocationBody,
        MessageType.CARD to CardBody,
        MessageType.REPLY to ReplyBody,
        MessageType.FORWARD to ForwardBody,
        MessageType.MERGE_FORWARD to MergeForwardBody,
        MessageType.REVOKE to RevokeBody,
        MessageType.EDIT to EditBody,
        MessageType.STICKER to StickerBody,
        MessageType.REACTION to ReactionBody,
        // GENERIC 也必须走严格 reader；这样未知 extensionType 的 opaque bytes 会被完整消费，
        // 不会在 Message 外层留下 trailing bytes 并被误判为损坏连接。
        MessageType.GENERIC to GenericPayload,
    )

    fun decode(messageType: MessageType?, buf: PacketBuffer): MessageBody? {
        if (messageType == null) return null
        val reader = readers[messageType] ?: return null
        return reader.readFrom(buf)
    }
}
