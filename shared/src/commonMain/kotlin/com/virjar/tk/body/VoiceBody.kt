package com.virjar.tk.body

import com.virjar.tk.model.MessageBody
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

data class VoiceBody(
    val url: String,
    val duration: Int = 0,
    val size: Long = 0,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(url)
        buf.writeVarInt(duration)
        buf.writeVarLong(size)
    }

    companion object : IProtoReader<VoiceBody> {
        override fun readFrom(buf: PacketBuffer) = VoiceBody(
            url = buf.readString()!!,
            duration = buf.readVarInt(),
            size = buf.readVarLong(),
        )
    }
}
