package com.virjar.tk.body

import com.virjar.tk.model.MessageBody
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

data class ImageBody(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    /** 服务端生成的缩略图 URL（尾部可选字段：旧端读到旧布局即止不崩溃；新端读剩余字节，旧消息为 null） */
    val thumbnailUrl: String? = null,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(url)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
        buf.writeVarLong(size)
        buf.writeString(thumbnailUrl)
    }

    companion object : IProtoReader<ImageBody> {
        override fun readFrom(buf: PacketBuffer) = ImageBody(
            url = buf.readString()!!,
            width = buf.readVarInt(),
            height = buf.readVarInt(),
            size = buf.readVarLong(),
            // 尾部可选：仅当还有剩余字节才读（旧消息/旧端互操作）
            thumbnailUrl = if (buf.readableBytes() > 0) buf.readString() else null,
        )
    }
}
