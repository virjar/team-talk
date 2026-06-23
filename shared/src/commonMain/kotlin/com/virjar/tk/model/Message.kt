package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.body.MessageBodyRegistry

/**
 * 消息传输模型。
 * wire format: [chatId][clientMsgId][serverSeq][senderUid][messageType][timestamp][flags][body bytes]
 */
data class Message(
    val chatId: String,
    val clientMsgId: String,
    val serverSeq: Long = 0,
    val senderUid: String,
    val messageType: Int,
    val timestamp: Long,
    val flags: Int = 0,
    val body: MessageBody? = null,
    /** 发送状态：0=sent, 1=sending, 2=failed。纯客户端字段，不参与协议传输。 */
    val sendStatus: Int = SEND_STATUS_SENT,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeString(clientMsgId)
        buf.writeVarLong(serverSeq)
        buf.writeString(senderUid)
        buf.writeByte(messageType)
        buf.writeVarLong(timestamp)
        buf.writeVarInt(flags)
        if (body != null) {
            buf.writeByte(1)
            body.writeTo(buf)
        } else {
            buf.writeByte(0)
        }
    }

    companion object : com.virjar.tk.protocol.IProtoReader<Message> {
        const val SEND_STATUS_SENT = 0
        const val SEND_STATUS_SENDING = 1
        const val SEND_STATUS_FAILED = 2

        /** flags 位定义（服务端 MessageService 设置，客户端用于渲染撤回/编辑/转发状态） */
        const val FLAG_REVOKED = 1   // bit0：消息已被撤回
        const val FLAG_EDITED = 2    // bit1：消息已被编辑
        const val FLAG_FORWARDED = 4 // bit2：消息是转发来的

        override fun readFrom(buf: PacketBuffer): Message {
            val chatId = buf.readString()!!
            val clientMsgId = buf.readString()!!
            val serverSeq = buf.readVarLong()
            val senderUid = buf.readString()!!
            val messageType = buf.readByte()
            val timestamp = buf.readVarLong()
            val flags = buf.readVarInt()
            val hasBody = buf.readByte() != 0
            val body = if (hasBody) {
                MessageBodyRegistry.decode(MessageType.fromCode(messageType), buf)
            } else null
            return Message(
                chatId = chatId,
                clientMsgId = clientMsgId,
                serverSeq = serverSeq,
                senderUid = senderUid,
                messageType = messageType,
                timestamp = timestamp,
                flags = flags,
                body = body,
            )
        }
    }
}

/**
 * 消息 Body 基接口。所有消息类型实现此接口。
 */
interface MessageBody : IProto
