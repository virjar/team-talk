package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.PacketBuffer

/**
 * NOTIFY payload — 服务端向客户端推送通知。
 * wire format: [eventId(varLong)][notifyType(1B)][payload bytes]
 */
data class NotifyPayload(
    val eventId: Long,
    val notifyType: Int,
    val payload: ByteArray?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarLong(eventId)
        buf.writeByte(notifyType)
        buf.writeBytes(payload)
    }

    companion object : IProtoReader<NotifyPayload> {
        override fun readFrom(buf: PacketBuffer) = NotifyPayload(
            eventId = buf.readVarLong(),
            notifyType = buf.readByte(),
            payload = buf.readBytes(),
        )
    }
}
