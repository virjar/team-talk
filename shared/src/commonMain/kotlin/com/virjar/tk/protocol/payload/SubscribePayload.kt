package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoCreator
import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf

/**
 * SUBSCRIBE(10) 订阅频道
 */
data class SubscribePayload(
    val channelId: String,
    val lastSeq: Long,
) : IProto {
    override val packetType = PacketType.SUBSCRIBE
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeVarInt(buf, lastSeq)
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
        lastSeq = IProto.readVarInt(buf),
    )

    companion object : IProtoCreator<SubscribePayload> {
        override fun create(buf: ByteBuf) = SubscribePayload(buf)
    }
}

/**
 * UNSUBSCRIBE(11) 退订频道
 */
data class UnsubscribePayload(
    val channelId: String,
) : IProto {
    override val packetType = PacketType.UNSUBSCRIBE
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
    }

    constructor(buf: ByteBuf) : this(
        channelId = IProto.readString(buf)!!,
    )

    companion object : IProtoCreator<UnsubscribePayload> {
        override fun create(buf: ByteBuf) = UnsubscribePayload(buf)
    }
}
