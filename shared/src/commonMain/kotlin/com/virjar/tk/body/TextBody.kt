package com.virjar.tk.body

import com.virjar.tk.model.MessageBody
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

data class TextBody(val text: String) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(text)
    }

    companion object : IProtoReader<TextBody> {
        override fun readFrom(buf: PacketBuffer): TextBody = TextBody(
            text = buf.readString()!!
        )
    }
}
