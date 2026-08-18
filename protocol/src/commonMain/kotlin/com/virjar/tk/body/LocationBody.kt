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
            latitude = buf.readString()!!.toDouble(),
            longitude = buf.readString()!!.toDouble(),
            title = buf.readString(),
            address = buf.readString(),
        )
    }
}
