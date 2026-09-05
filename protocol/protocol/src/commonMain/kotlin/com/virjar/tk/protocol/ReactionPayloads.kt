package com.virjar.tk.protocol

import com.virjar.tk.protocol.body.MessageBodyPolicy

/**
 * MESSAGE_REACTION 通知 payload：一个成员对一条消息的一次幂等增/删。
 *
 * action: 1=添加, 0=移除（与 ReactionBody 一致）。客户端按
 * (chatId, serverSeq, emoji, actorUid) 精确投影行级 delta；重放/重复事件收敛到同一状态，
 * 聚合计数永远由服务端 [com.virjar.tk.protocol.model.MessageReactionSummary] 快照权威。
 */
data class MessageReactionEventPayload(
    val chatId: String,
    val serverSeq: Long,
    val emoji: String,
    val actorUid: String,
    val action: Int,
) : IProto {
    init {
        require(chatId.isNotBlank() && chatId.length <= MessageBodyPolicy.MAX_CHAT_ID_LENGTH) {
            "reaction chatId 非法"
        }
        MessageBodyPolicy.requireReactionEmoji(emoji, "reaction 表情")
        require(actorUid.isNotBlank() && actorUid.length <= MessageBodyPolicy.MAX_IDENTIFIER_LENGTH) {
            "reaction actorUid 非法"
        }
        require(action == 0 || action == 1) { "reaction action 非法" }
    }

    val added: Boolean get() = action == 1

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeVarLong(serverSeq)
        buf.writeString(emoji)
        buf.writeString(actorUid)
        buf.writeVarInt(action)
    }

    companion object : IProtoReader<MessageReactionEventPayload> {
        override fun readFrom(buf: PacketBuffer): MessageReactionEventPayload = try {
            MessageReactionEventPayload(
                chatId = buf.readRequiredString(
                    MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CHAT_ID_LENGTH),
                ),
                serverSeq = buf.readVarLong(),
                emoji = buf.readRequiredString(
                    MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_EMOJI_LENGTH),
                ),
                actorUid = buf.readRequiredString(
                    MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
                ),
                action = buf.readVarInt(),
            )
        } catch (invalid: IllegalArgumentException) {
            throw ProtocolCorruptionException(invalid.message ?: "Invalid message reaction event")
        }
    }
}
