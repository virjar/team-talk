package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.*
import kotlinx.serialization.Serializable

/** revision zero is absent; a cleared draft retains its positive revision as a tombstone. */
@SinceProtocol(2)
@Serializable
data class ChatDraftSnapshot(
    val chatId: String,
    val revision: Long,
    val updatedAt: Long,
    val content: ChatDraftContent?,
    val assetsAvailable: Boolean = true,
) : IProto {
    init { requireChatDraftChatId(chatId); require(revision >= 0 && updatedAt >= 0) }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId); buf.writeVarLong(revision); buf.writeVarLong(updatedAt)
        buf.writeBoolean(content != null); content?.writeTo(buf); buf.writeBoolean(assetsAvailable)
    }
    companion object : IProtoReader<ChatDraftSnapshot> {
        override fun readFrom(buf: PacketBuffer) = ChatDraftSnapshot(buf.readRequiredString(144), buf.readVarLong(),
            buf.readVarLong(), if (buf.readBoolean()) ChatDraftContent.readFrom(buf) else null, buf.readBoolean())
    }
}

fun requireChatDraftChatId(chatId: String) {
    require(chatId.isNotBlank() && chatId.length <= ConversationWirePolicy.MAX_CHAT_ID_LENGTH)
}
