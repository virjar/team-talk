package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

data class Conversation(
    val chatId: String,
    val chatType: Int,
    val chatName: String? = null,
    val chatAvatar: String? = null,
    val lastMessage: String? = null,
    val lastMessageType: Int? = null,
    val lastMsgTimestamp: Long? = null,
    val lastSeq: Long = 0,
    val readSeq: Long = 0,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val peerReadSeq: Long = 0,
    val draft: String? = null,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeVarInt(chatType)
        buf.writeString(chatName)
        buf.writeString(chatAvatar)
        buf.writeString(lastMessage)
        // nullable Int: 0=present, 1=null
        if (lastMessageType != null) {
            buf.writeByte(1)
            buf.writeVarInt(lastMessageType)
        } else {
            buf.writeByte(0)
        }
        // nullable Long
        if (lastMsgTimestamp != null) {
            buf.writeByte(1)
            buf.writeVarLong(lastMsgTimestamp)
        } else {
            buf.writeByte(0)
        }
        buf.writeVarLong(lastSeq)
        buf.writeVarLong(readSeq)
        buf.writeVarInt(unreadCount)
        buf.writeByte(if (isPinned) 1 else 0)
        buf.writeByte(if (isMuted) 1 else 0)
        buf.writeVarLong(peerReadSeq)
        buf.writeString(draft)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<Conversation> {
        override fun readFrom(buf: PacketBuffer): Conversation {
            val chatId = buf.readString()!!
            val chatType = buf.readVarInt()
            val chatName = buf.readString()
            val chatAvatar = buf.readString()
            val lastMessage = buf.readString()
            val lastMessageType = if (buf.readByte() != 0) buf.readVarInt() else null
            val lastMsgTimestamp = if (buf.readByte() != 0) buf.readVarLong() else null
            val lastSeq = buf.readVarLong()
            val readSeq = buf.readVarLong()
            val unreadCount = buf.readVarInt()
            val isPinned = buf.readByte() != 0
            val isMuted = buf.readByte() != 0
            val peerReadSeq = buf.readVarLong()
            val draft = buf.readString()
            return Conversation(
                chatId = chatId,
                chatType = chatType,
                chatName = chatName,
                chatAvatar = chatAvatar,
                lastMessage = lastMessage,
                lastMessageType = lastMessageType,
                lastMsgTimestamp = lastMsgTimestamp,
                lastSeq = lastSeq,
                readSeq = readSeq,
                unreadCount = unreadCount,
                isPinned = isPinned,
                isMuted = isMuted,
                peerReadSeq = peerReadSeq,
                draft = draft,
            )
        }
    }
}
