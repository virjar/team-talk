package com.virjar.tk.model

import kotlinx.serialization.Serializable

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

@Serializable
data class Chat(
    val chatId: String,
    val chatType: Int,       // 1=personal, 2=group
    val name: String? = null,
    val avatar: String? = null,
    val creator: String? = null,
    val memberCount: Int = 0,
    val maxSeq: Long = 0,
    val notice: String? = null,
    val mutedAll: Boolean = false,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeVarInt(chatType)
        buf.writeString(name)
        buf.writeString(avatar)
        buf.writeString(creator)
        buf.writeVarInt(memberCount)
        buf.writeVarLong(maxSeq)
        buf.writeString(notice)
        buf.writeBoolean(mutedAll)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<Chat> {
        override fun readFrom(buf: PacketBuffer): Chat = Chat(
            chatId = buf.readRequiredString(),
            chatType = buf.readVarInt(),
            name = buf.readString(),
            avatar = buf.readString(),
            creator = buf.readString(),
            memberCount = buf.readVarInt(),
            maxSeq = buf.readVarLong(),
            notice = buf.readString(),
            mutedAll = buf.readBoolean("chat mutedAll"),
        )
    }
}
