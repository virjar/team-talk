package com.virjar.tk.protocol.payload

import com.virjar.tk.model.Message
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

/**
 * MESSAGE_ACK payload — 服务端确认客户端发送的消息。
 */
data class MessageAckPayload(
    val clientMsgId: String,
    val serverSeq: Long,
    val code: Int,      // 0=OK, 非0=失败
    val reason: String? = null,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(clientMsgId)
        buf.writeVarLong(serverSeq)
        buf.writeVarInt(code)
        buf.writeString(reason)
    }

    companion object : IProtoReader<MessageAckPayload> {
        override fun readFrom(buf: PacketBuffer) = MessageAckPayload(
            clientMsgId = buf.readString()!!,
            serverSeq = buf.readVarLong(),
            code = buf.readVarInt(),
            reason = buf.readString(),
        )
    }
}

/**
 * SUBSCRIBE payload。
 */
data class SubscribePayload(
    val chatId: String,
    val lastSeq: Long = 0,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeVarLong(lastSeq)
    }

    companion object : IProtoReader<SubscribePayload> {
        override fun readFrom(buf: PacketBuffer) = SubscribePayload(
            chatId = buf.readString()!!,
            lastSeq = buf.readVarLong(),
        )
    }
}

/**
 * UNSUBSCRIBE payload。
 */
data class UnsubscribePayload(
    val chatId: String,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
    }

    companion object : IProtoReader<UnsubscribePayload> {
        override fun readFrom(buf: PacketBuffer) = UnsubscribePayload(
            chatId = buf.readString()!!,
        )
    }
}
