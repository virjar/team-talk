package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf
import kotlinx.serialization.json.*

/**
 * 消息组合对象：Header + Body。
 *
 * Header 持有所有消息类型共享的字段（channelId/messageId/seq/timestamp/flags 等），
 * Body 持有各消息类型特有的字段。两者独立编解码。
 *
 * Wire format: [headerLen(2)][header bytes][body bytes]
 */
class Message(
    val header: MessageHeader,
    val body: MessageBody,
) : IProto {
    override val packetType: PacketType get() = body.packetType

    // 便捷属性，委托到 header
    val channelId get() = header.channelId
    val clientMsgNo get() = header.clientMsgNo
    val clientSeq get() = header.clientSeq
    val messageId get() = header.messageId
    val senderUid get() = header.senderUid
    val channelType get() = header.channelType
    val serverSeq get() = header.serverSeq
    val timestamp get() = header.timestamp
    val flags get() = header.flags

    override fun writeTo(buf: ByteBuf) {
        val sizeIndex = buf.writerIndex()
        buf.writeShort(0) // placeholder for header length
        header.writeTo(buf)
        val headerSize = buf.writerIndex() - sizeIndex - 2
        buf.setShort(sizeIndex, headerSize)
        body.writeTo(buf)
    }

    companion object {
        /** 从 ByteBuf 反序列化（wire format: [headerLen(2)][header bytes][body bytes]） */
        fun readFrom(buf: ByteBuf, packetType: PacketType): Message {
            val headerLen = buf.readUnsignedShort()
            val headerBuf = buf.retainedSlice(buf.readerIndex(), headerLen)
            buf.skipBytes(headerLen)
            try {
                val header = MessageHeader.readFrom(headerBuf)
                val bodyCreator = PacketType.bodyCreatorFor<MessageBody>(packetType)
                    ?: throw IllegalStateException("No body creator for $packetType")
                val body = bodyCreator.create(buf)
                return Message(header, body)
            } finally {
                headerBuf.release()
            }
        }

        /** 根据 PacketType 和 body JSON 创建 MessageBody */
        fun bodyFromJson(packetType: PacketType, json: JsonObject): MessageBody? {
            return PacketType.bodyCreatorFor<MessageBody>(packetType)?.fromJson(json)
        }

        /** 从 MessageBody 对象提取预览文本 */
        fun extractPreviewText(body: MessageBody): String {
            return when (body) {
                is TextBody -> body.text
                is ImageBody -> "[Image]"
                is VoiceBody -> "[Voice]"
                is VideoBody -> "[Video]"
                is FileBody -> "[File] ${body.fileName}"
                is ReplyBody -> "[Reply] ${body.text}"
                is ForwardBody -> "[Forward]"
                is MergeForwardBody -> "[Chat Record] ${body.messages.size} messages"
                is LocationBody -> "[Location]"
                is CardBody -> "[Card]"
                is StickerBody -> "[Sticker]"
                is InteractiveBody -> "[Card Message]"
                is RichBody -> "[Rich Text]"
                else -> ""
            }
        }

        /** 检测消息是否为编辑过的（flags bit 0 = edited） */
        fun isEdited(flags: Int): Boolean = (flags and 1) != 0
    }
}
