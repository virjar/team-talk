package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.body.MessageBody
import com.virjar.tk.protocol.body.MessageBodyRegistry
import kotlinx.serialization.Transient

/**
 * 定时发送调度请求（MSG-01/F3）。
 *
 * [body] 与 [com.virjar.tk.protocol.model.Message] 的正文同源：发送侧经 body 编码器序列化，
 * 服务端到点投递时走与 MESSAGE 帧相同的正文与附件校验。wire 传输不复用
 * `MessageBody` 接口直接字段——与 Message 一致，readFrom 按 messageType 查注册表。
 */
@com.virjar.tk.protocol.SinceProtocol(6)
data class ScheduledMessageDraft(
    val chatId: String,
    val messageType: Int,
    val deliverAt: Long,
    /** 本命令的稳定 UUID；重试复用同值返回原调度记录，不产生第二条。 */
    val operationId: String,
    @Transient
    val body: MessageBody? = null,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeByte(messageType)
        buf.writeVarLong(deliverAt)
        buf.writeString(operationId)
        buf.writeBoolean(body != null)
        body?.writeTo(buf)
    }

    companion object : IProtoReader<ScheduledMessageDraft> {
        override fun readFrom(buf: PacketBuffer): ScheduledMessageDraft {
            val chatId = buf.readRequiredString(MAX_CHAT_ID_LENGTH, "schedule chatId")
            val messageType = buf.readByte()
            val deliverAt = buf.readVarLong()
            val operationId = buf.readRequiredString(MAX_OPERATION_ID_LENGTH, "schedule operationId")
            val hasBody = buf.readBoolean("schedule body presence")
            val body = if (hasBody) {
                MessageBodyRegistry.decode(MessageType.fromCode(messageType), buf)
            } else null
            return ScheduledMessageDraft(chatId, messageType, deliverAt, operationId, body)
        }

        /** 与 MessageBodyPolicy 的 chatId 容量契约一致。 */
        const val MAX_CHAT_ID_LENGTH = 256
        const val MAX_OPERATION_ID_LENGTH = 64
    }
}
