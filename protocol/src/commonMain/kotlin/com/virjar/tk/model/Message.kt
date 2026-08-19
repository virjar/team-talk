package com.virjar.tk.model

import kotlinx.serialization.Serializable

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.body.MessageBodyRegistry
import com.virjar.tk.body.MessageBody
import com.virjar.tk.body.MessageBodyPolicy

/**
 * 消息传输模型。
 * wire format: [chatId][clientMsgId][serverSeq][senderUid][messageType][timestamp][flags][body bytes]
 */
@Serializable
data class Message(
    val chatId: String,
    val clientMsgId: String,
    val serverSeq: Long = 0,
    val senderUid: String,
    val messageType: Int,
    val timestamp: Long,
    val flags: Int = 0,
    // JSON 序列化跳过多态 body（15 种 Body 子类未标 @Serializable；admin 高亮走 Lucene，
    // wire 编解码走 IProto.writeTo 不受影响）
    @kotlinx.serialization.Transient
    val body: MessageBody? = null,
    /** 发送状态：0=sent, 1=sending, 2=failed, 3=uploading。纯客户端字段，不参与协议传输。 */
    val sendStatus: Int = SEND_STATUS_SENT,
    /** 上传进度 0..1（媒体上传中动画）。纯 UI 状态，不持久化不传输。 */
    @kotlinx.serialization.Transient
    val uploadProgress: Float = 0f,
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
        const val SEND_STATUS_UPLOADING = 3
        const val SEND_STATUS_QUEUED = 4 // 断线排队（SendQueue；重连后自动补发）

        /** flags 位定义（服务端 MessageService 设置，客户端用于渲染撤回/编辑/转发状态） */
        const val FLAG_REVOKED = 1   // bit0：消息已被撤回
        const val FLAG_EDITED = 2    // bit1：消息已被编辑
        const val FLAG_FORWARDED = 4 // bit2：消息是转发来的

        override fun readFrom(buf: PacketBuffer): Message {
            val chatId = buf.readString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CHAT_ID_LENGTH))!!
            val clientMsgId = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH),
            )!!
            val serverSeq = buf.readVarLong()
            val senderUid = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            )!!
            val messageType = buf.readByte()
            val timestamp = buf.readVarLong()
            val flags = buf.readVarInt()
            val hasBody = buf.readPresenceFlag("message body")
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
