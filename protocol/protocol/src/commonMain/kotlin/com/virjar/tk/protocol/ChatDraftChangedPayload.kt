package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.requireChatDraftChatId

@SinceProtocol(2)
data class ChatDraftChangedPayload(val chatId: String, val revision: Long) : IProto {
    init { requireChatDraftChatId(chatId); require(revision > 0) }
    override fun writeTo(buf: PacketBuffer) { buf.writeString(chatId); buf.writeVarLong(revision) }
    companion object : IProtoReader<ChatDraftChangedPayload> {
        override fun readFrom(buf: PacketBuffer) = ChatDraftChangedPayload(buf.readRequiredString(144), buf.readVarLong())
    }
}
