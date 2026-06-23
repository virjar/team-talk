package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

// ── 认证请求 ──

data class AuthRequestPayload(
    val authType: Int,      // 0=login, 1=register, 2=refresh
    val username: String? = null,
    val password: String? = null,
    val name: String? = null,
    val refreshToken: String? = null,
    val deviceId: String,
    val deviceName: String? = null,
    val deviceModel: String? = null,
    val deviceFlag: Int = 0,
    val protocolVersion: Int = 1,
    val lastEventId: Long = 0,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(authType)
        buf.writeString(username)
        buf.writeString(password)
        buf.writeString(name)
        buf.writeString(refreshToken)
        buf.writeString(deviceId)
        buf.writeString(deviceName)
        buf.writeString(deviceModel)
        buf.writeVarInt(deviceFlag)
        buf.writeVarInt(protocolVersion)
        buf.writeVarLong(lastEventId)
    }

    companion object : IProtoReader<AuthRequestPayload> {
        override fun readFrom(buf: PacketBuffer) = AuthRequestPayload(
            authType = buf.readVarInt(),
            username = buf.readString(),
            password = buf.readString(),
            name = buf.readString(),
            refreshToken = buf.readString(),
            deviceId = buf.readString()!!,
            deviceName = buf.readString(),
            deviceModel = buf.readString(),
            deviceFlag = buf.readVarInt(),
            protocolVersion = buf.readVarInt(),
            lastEventId = buf.readVarLong(),
        )
    }
}

// ── 认证响应 ──

data class AuthResponsePayload(
    val code: Int,          // 0=OK, 1=auth_failed, 2=version_unsupported, 3=server_maintenance, 4=device_banned, 5=too_many_connections
    val reason: String? = null,
    val uid: String? = null,
    val username: String? = null,
    val name: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long = 0,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(code)
        buf.writeString(reason)
        buf.writeString(uid)
        buf.writeString(username)
        buf.writeString(name)
        buf.writeString(accessToken)
        buf.writeString(refreshToken)
        buf.writeVarLong(expiresIn)
    }

    companion object : IProtoReader<AuthResponsePayload> {
        override fun readFrom(buf: PacketBuffer) = AuthResponsePayload(
            code = buf.readVarInt(),
            reason = buf.readString(),
            uid = buf.readString(),
            username = buf.readString(),
            name = buf.readString(),
            accessToken = buf.readString(),
            refreshToken = buf.readString(),
            expiresIn = buf.readVarLong(),
        )
    }
}
