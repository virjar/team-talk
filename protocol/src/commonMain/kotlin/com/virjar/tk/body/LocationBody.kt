package com.virjar.tk.body

import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

data class LocationBody(
    val latitude: Double,
    val longitude: Double,
    val title: String? = null,
    val address: String? = null,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(latitude.toString())
        buf.writeString(longitude.toString())
        buf.writeString(title)
        buf.writeString(address)
    }

    companion object : IProtoReader<LocationBody> {
        override fun readFrom(buf: PacketBuffer) = LocationBody(
            latitude = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_COORDINATE_TEXT_LENGTH),
            ).toDouble(),
            longitude = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_COORDINATE_TEXT_LENGTH),
            ).toDouble(),
            title = buf.readString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH)),
            address = buf.readString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_SHORT_TEXT_LENGTH)),
        )
    }
}
