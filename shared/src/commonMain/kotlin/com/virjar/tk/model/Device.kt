package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer

data class Device(
    val deviceId: String,
    val deviceName: String? = null,
    val deviceModel: String? = null,
    val deviceFlag: Int = 0,
    val lastLogin: Long = 0,
    val isOnline: Boolean = false,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(deviceId)
        buf.writeString(deviceName)
        buf.writeString(deviceModel)
        buf.writeVarInt(deviceFlag)
        buf.writeVarLong(lastLogin)
        buf.writeByte(if (isOnline) 1 else 0)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<Device> {
        override fun readFrom(buf: PacketBuffer): Device = Device(
            deviceId = buf.readString()!!,
            deviceName = buf.readString(),
            deviceModel = buf.readString(),
            deviceFlag = buf.readVarInt(),
            lastLogin = buf.readVarLong(),
            isOnline = buf.readByte() != 0,
        )
    }
}
