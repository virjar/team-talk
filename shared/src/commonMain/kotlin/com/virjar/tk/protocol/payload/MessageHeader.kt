package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.IProto
import io.netty.buffer.ByteBuf
import kotlinx.serialization.json.*

/**
 * 消息头部：持有 9 个所有消息类型共享的字段 + flags。
 * 不可变 data class，与 MessageBody 组合使用。
 */
data class MessageHeader(
    val channelId: String = "",
    val clientMsgNo: String = "",
    val clientSeq: Long = 0,
    val messageId: String? = null,
    val senderUid: String? = null,
    val channelType: ChannelType = ChannelType.PERSONAL,
    val serverSeq: Long = 0,
    val timestamp: Long = 0,
    val flags: Int = 0,
) {
    fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeString(buf, clientMsgNo)
        IProto.writeVarInt(buf, clientSeq)
        IProto.writeString(buf, messageId)
        IProto.writeString(buf, senderUid)
        buf.writeByte(channelType.code)
        IProto.writeVarInt(buf, serverSeq)
        IProto.writeVarInt(buf, timestamp)
        IProto.writeVarInt(buf, flags.toLong())
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("messageId", messageId ?: "")
        put("channelId", channelId)
        put("channelType", channelType.code)
        put("senderUid", senderUid ?: "")
        put("seq", serverSeq)
        put("clientSeq", clientSeq)
        put("clientMsgNo", clientMsgNo)
        put("timestamp", timestamp)
        put("flags", flags)
    }

    companion object {
        fun readFrom(buf: ByteBuf): MessageHeader {
            return MessageHeader(
                channelId = IProto.readString(buf)!!,
                clientMsgNo = IProto.readString(buf)!!,
                clientSeq = IProto.readVarInt(buf),
                messageId = IProto.readString(buf),
                senderUid = IProto.readString(buf),
                channelType = ChannelType.fromCode(buf.readByte().toInt()),
                serverSeq = IProto.readVarInt(buf),
                timestamp = IProto.readVarInt(buf),
                flags = IProto.readVarInt(buf).toInt(),
            )
        }

        fun fromJson(json: JsonObject): MessageHeader {
            return MessageHeader(
                channelId = json["channelId"]?.jsonPrimitive?.content ?: "",
                clientMsgNo = json["clientMsgNo"]?.jsonPrimitive?.contentOrNull ?: "",
                clientSeq = json["clientSeq"]?.jsonPrimitive?.longOrNull ?: 0L,
                messageId = json["messageId"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null },
                senderUid = json["senderUid"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null },
                channelType = ChannelType.fromCode(json["channelType"]?.jsonPrimitive?.intOrNull ?: 1),
                serverSeq = json["seq"]?.jsonPrimitive?.longOrNull ?: 0L,
                timestamp = json["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
                flags = json["flags"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }
}
