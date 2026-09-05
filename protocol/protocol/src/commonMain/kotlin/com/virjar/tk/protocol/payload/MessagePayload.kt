package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

/**
 * MESSAGE_ACK payload — 服务端以 chatId + clientMsgId 确认客户端发送的消息。
 */
data class MessageAckPayload(
    val chatId: String,
    val clientMsgId: String,
    val serverSeq: Long,
    val code: Int,      // 0=成功, 非0=失败
    val reason: String? = null,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeString(clientMsgId)
        buf.writeVarLong(serverSeq)
        buf.writeVarInt(code)
        buf.writeString(reason)
    }

    companion object : IProtoReader<MessageAckPayload> {
        override fun readFrom(buf: PacketBuffer) = MessageAckPayload(
            chatId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CHAT_ID_LENGTH),
            ),
            clientMsgId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH),
            ),
            serverSeq = buf.readVarLong(),
            code = buf.readVarInt(),
            reason = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_SHORT_TEXT_LENGTH),
            ),
        )
    }
}
