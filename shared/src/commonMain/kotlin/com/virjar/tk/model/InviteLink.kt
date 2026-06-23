package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

data class InviteLink(
    val token: String,
    val chatId: String,
    val name: String,
    val maxUses: Int,
    val useCount: Int,
    val expiresAt: Long,
    val revokedAt: Long,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(token)
        buf.writeString(chatId)
        buf.writeString(name)
        buf.writeVarInt(maxUses)
        buf.writeVarInt(useCount)
        buf.writeVarLong(expiresAt)
        buf.writeVarLong(revokedAt)
    }

    companion object : IProtoReader<InviteLink> {
        override fun readFrom(buf: PacketBuffer): InviteLink = InviteLink(
            token = buf.readString()!!,
            chatId = buf.readString()!!,
            name = buf.readString() ?: "",
            maxUses = buf.readVarInt(),
            useCount = buf.readVarInt(),
            expiresAt = buf.readVarLong(),
            revokedAt = buf.readVarLong(),
        )
    }
}
