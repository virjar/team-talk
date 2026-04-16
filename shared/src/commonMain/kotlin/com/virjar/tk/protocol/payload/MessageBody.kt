package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf
import kotlinx.serialization.json.JsonObject

/**
 * 消息体接口：各消息类型只定义自己的 body 字段。
 * 不含 header 字段（channelId/messageId/seq/timestamp/flags 等）。
 */
interface MessageBody {
    val packetType: PacketType

    /** 将 body 写入 ByteBuf（不含 header） */
    fun writeTo(buf: ByteBuf)

    /** 将 body 转为 JSON（不含 header 字段，不含 flags） */
    fun toJson(): JsonObject
}

/**
 * MessageBody 的工厂接口。每个 Body 的 companion object 实现此接口，
 * 供 PacketCodec 自动反序列化。
 */
interface MessageBodyCreator<T : MessageBody> {
    fun create(buf: ByteBuf): T
}
