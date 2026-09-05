package com.virjar.tk.protocol.model

import kotlinx.serialization.Serializable

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.body.MessageBodyRegistry
import com.virjar.tk.protocol.body.MessageBody
import com.virjar.tk.protocol.body.MessageBodyPolicy

/**
 * 消息传输模型。
 * 线格式：[chatId][clientMsgId][serverSeq][senderUid][messageType][timestamp][flags][body bytes]
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
    // JSON 序列化跳过多态 body（MessageBody 子类未标 @Serializable；admin 高亮走 Lucene，
    // wire 编解码走 IProto.writeTo 不受影响）
    @kotlinx.serialization.Transient
    val body: MessageBody? = null,
    /** 发送状态：0=已发送, 1=发送中, 2=失败, 3=上传中。纯客户端字段，不参与协议传输。 */
    @com.virjar.tk.protocol.ProtocolLocal
    val sendStatus: Int = SEND_STATUS_SENT,
    /** 上传进度 0..1（媒体上传中动画）。纯 UI 状态，不持久化不传输。 */
    @kotlinx.serialization.Transient
    @com.virjar.tk.protocol.ProtocolLocal
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
        buf.writeBoolean(body != null)
        body?.writeTo(buf)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<Message> {
        /** 历史与搜索 RPC 请求及响应页的跨端上限。 */
        const val MAX_QUERY_PAGE_SIZE = 10

        const val SEND_STATUS_SENT = 0
        const val SEND_STATUS_SENDING = 1
        const val SEND_STATUS_FAILED = 2
        const val SEND_STATUS_UPLOADING = 3
        const val SEND_STATUS_QUEUED = 4 // 断线排队（SendQueue；重连后自动补发）

        /** flags 位定义（服务端 MessageService 设置，客户端用于渲染撤回/编辑/转发状态） */
        const val FLAG_REVOKED = 1   // bit0：消息已被撤回
        const val FLAG_EDITED = 2    // bit1：消息已被编辑
        const val FLAG_FORWARDED = 4 // bit2：消息是转发来的

        /** 连接投影只需类型身份；共用有界头部 decoder，避免解码/分配整份消息正文。 */
        fun readMessageType(payload: ByteArray): Int = readHeader(PacketBuffer(payload)).messageType

        private data class Header(
            val chatId: String,
            val clientMsgId: String,
            val serverSeq: Long,
            val senderUid: String,
            val messageType: Int,
        )

        private fun readHeader(buf: PacketBuffer): Header {
            val chatId = buf.readRequiredString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CHAT_ID_LENGTH))
            val clientMsgId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH),
            )
            val serverSeq = buf.readVarLong()
            val senderUid = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            )
            val messageType = buf.readByte()
            return Header(chatId, clientMsgId, serverSeq, senderUid, messageType)
        }

        override fun readFrom(buf: PacketBuffer): Message {
            val header = readHeader(buf)
            val timestamp = buf.readVarLong()
            val flags = buf.readVarInt()
            val hasBody = buf.readBoolean("message body presence")
            val body = if (hasBody) {
                MessageBodyRegistry.decode(MessageType.fromCode(header.messageType), buf)
            } else null
            return Message(
                chatId = header.chatId,
                clientMsgId = header.clientMsgId,
                serverSeq = header.serverSeq,
                senderUid = header.senderUid,
                messageType = header.messageType,
                timestamp = timestamp,
                flags = flags,
                body = body,
            )
        }
    }
}
