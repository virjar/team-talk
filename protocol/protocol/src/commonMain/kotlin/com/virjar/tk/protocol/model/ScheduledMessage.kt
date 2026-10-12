package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.body.MessageBody
import com.virjar.tk.protocol.WireCoded
import com.virjar.tk.protocol.body.MessageBodyRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 定时发送调度记录（MSG-01/F3）：服务端权威事实，`scheduledId` 由服务端分配。
 *
 * 状态：0=WAITING 等待到点投递；1=DELIVERED 已按原消息链路落库；
 * 2=CANCELLED 发起者取消；3=FAILED 投递失败（如附件失效），保留在列表由发起者处理。
 */
@com.virjar.tk.protocol.SinceProtocol(6)
data class ScheduledMessage(
    val scheduledId: String,
    val chatId: String,
    val senderUid: String,
    val messageType: Int,
    val deliverAt: Long,
    val status: ScheduledMessageStatus,
    val createdAt: Long,
    val operationId: String,
    @Transient
    val body: MessageBody? = null,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(scheduledId)
        buf.writeString(chatId)
        buf.writeString(senderUid)
        buf.writeByte(messageType)
        buf.writeVarLong(deliverAt)
        buf.writeByte(status.code)
        buf.writeVarLong(createdAt)
        buf.writeString(operationId)
        buf.writeBoolean(body != null)
        body?.writeTo(buf)
    }

    companion object : IProtoReader<ScheduledMessage> {

        override fun readFrom(buf: PacketBuffer): ScheduledMessage {
            val scheduledId = buf.readRequiredString(MAX_ID_LENGTH, "scheduledId")
            val chatId = buf.readRequiredString(MAX_ID_LENGTH, "scheduled chatId")
            val senderUid = buf.readRequiredString(MAX_ID_LENGTH, "scheduled senderUid")
            val messageType = buf.readByte()
            val deliverAt = buf.readVarLong()
            val status = ScheduledMessageStatus.fromCode(buf.readByte().toInt())
            val createdAt = buf.readVarLong()
            val operationId = buf.readRequiredString(MAX_ID_LENGTH, "scheduled operationId")
            val hasBody = buf.readBoolean("scheduled body presence")
            val body = if (hasBody) {
                MessageBodyRegistry.decode(MessageType.fromCode(messageType), buf)
            } else null
            return ScheduledMessage(scheduledId, chatId, senderUid, messageType, deliverAt, status, createdAt, operationId, body)
        }

        const val MAX_ID_LENGTH = 256
    }
}

/**
 * 定时消息状态；与 scheduled_messages 表 status 列共用值空间。
 * 未知取值承载为 [ScheduledMessageStatus.Unknown]，按已终结展示。
 */
@Serializable(with = ScheduledMessageStatus.CodeSerializer::class)
sealed interface ScheduledMessageStatus : WireCoded {
    data object WAITING : ScheduledMessageStatus {
        override val code = 0
    }
    data object DELIVERED : ScheduledMessageStatus {
        override val code = 1
    }
    data object CANCELLED : ScheduledMessageStatus {
        override val code = 2
    }
    data object FAILED : ScheduledMessageStatus {
        override val code = 3
    }

    data class Unknown(override val code: Int) : ScheduledMessageStatus

    object CodeSerializer : com.virjar.tk.protocol.WireCodedSerializer<ScheduledMessageStatus>("ScheduledMessageStatus") {
        override fun fromCode(code: Int) = ScheduledMessageStatus.fromCode(code)
    }

    companion object {
        fun fromCode(code: Int): ScheduledMessageStatus = when (code) {
            WAITING.code -> WAITING
            DELIVERED.code -> DELIVERED
            CANCELLED.code -> CANCELLED
            FAILED.code -> FAILED
            else -> Unknown(code)
        }
    }
}
