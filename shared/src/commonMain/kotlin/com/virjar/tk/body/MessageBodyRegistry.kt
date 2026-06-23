package com.virjar.tk.body

import com.virjar.tk.model.MessageBody
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer

/**
 * 消息 Body 编解码注册表。
 * messageType -> Body 的 IProtoReader 映射。
 */
object MessageBodyRegistry {

    private val readers: Map<MessageType, IProtoReader<out MessageBody>> = mapOf(
        MessageType.TEXT to TextBody,
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
    )

    fun decode(messageType: MessageType?, buf: PacketBuffer): MessageBody? {
        if (messageType == null) return null
        val reader = readers[messageType] ?: return null
        return reader.readFrom(buf)
    }
}
