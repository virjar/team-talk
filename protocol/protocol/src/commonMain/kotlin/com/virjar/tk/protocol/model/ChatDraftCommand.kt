package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.body.MessageBodyPolicy
import kotlinx.serialization.Serializable

@SinceProtocol(2)
@Serializable
data class ChatDraftCommand(
    val chatId: String,
    val expectedRevision: Long,
    val operationId: String,
    val issuedAt: Long,
    val content: ChatDraftContent? = null,
    /** A send-consume may clear only after this exact message is authoritatively accepted. */
    val consumedClientMsgId: String? = null,
) : IProto {
    init {
        requireChatDraftChatId(chatId); EmbeddedAsset.requireCanonicalAssetId(operationId)
        require(expectedRevision in 0 until Long.MAX_VALUE && issuedAt >= 0)
        consumedClientMsgId?.let {
            require(content == null && it.isNotBlank() && it.length <= MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH)
        }
    }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId); buf.writeVarLong(expectedRevision); buf.writeString(operationId); buf.writeVarLong(issuedAt)
        buf.writeBoolean(content != null); content?.writeTo(buf); buf.writeString(consumedClientMsgId)
    }
    companion object : IProtoReader<ChatDraftCommand> {
        override fun readFrom(buf: PacketBuffer) = ChatDraftCommand(buf.readRequiredString(144), buf.readVarLong(),
            buf.readRequiredString(36), buf.readVarLong(), if (buf.readBoolean()) ChatDraftContent.readFrom(buf) else null,
            buf.readString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH)))
    }
}
