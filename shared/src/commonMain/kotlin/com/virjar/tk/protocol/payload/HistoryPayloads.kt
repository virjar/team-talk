package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoCreator
import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf

/**
 * HISTORY_LOAD(12) 客户端请求加载历史消息
 */
data class HistoryLoadPayload(
    val channelId: String,
    val beforeSeq: Long,
    val limit: Int,
) : IProto {
    override val packetType = PacketType.HISTORY_LOAD
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeVarInt(buf, beforeSeq)
        IProto.writeVarInt(buf, limit.toLong())
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        beforeSeq = IProto.readVarInt(buf),
        limit = IProto.readVarInt(buf).toInt(),
    )

    companion object : IProtoCreator<HistoryLoadPayload> {
        override fun create(buf: ByteBuf) = HistoryLoadPayload(buf)
    }
}

/**
 * HISTORY_LOAD_END(13) 服务端通知历史消息推送完毕
 */
data class HistoryLoadEndPayload(
    val channelId: String,
    val beforeSeq: Long,
    val hasMore: Boolean,
) : IProto {
    override val packetType = PacketType.HISTORY_LOAD_END
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeVarInt(buf, beforeSeq)
        buf.writeBoolean(hasMore)
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        beforeSeq = IProto.readVarInt(buf),
        hasMore = buf.readBoolean(),
    )

    companion object : IProtoCreator<HistoryLoadEndPayload> {
        override fun create(buf: ByteBuf) = HistoryLoadEndPayload(buf)
    }
}
