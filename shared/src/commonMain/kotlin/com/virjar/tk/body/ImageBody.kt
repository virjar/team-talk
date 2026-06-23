package com.virjar.tk.body

import com.virjar.tk.model.MessageBody
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

data class ImageBody(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(url)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
        buf.writeVarLong(size)
    }

    companion object : IProtoReader<ImageBody> {
        override fun readFrom(buf: PacketBuffer) = ImageBody(
            url = buf.readString()!!,
            width = buf.readVarInt(),
            height = buf.readVarInt(),
            size = buf.readVarLong(),
        )
    }
}
