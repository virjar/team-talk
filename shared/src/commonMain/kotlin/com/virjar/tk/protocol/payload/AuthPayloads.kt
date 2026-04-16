package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoCreator
import com.virjar.tk.protocol.PacketType
import io.netty.buffer.ByteBuf


data class AuthRequestPayload(
    val token: String,
    val uid: String,
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val clientVersion: String,
    val flags: Byte
) : IProto {
    override val packetType = PacketType.AUTH
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, token)
        IProto.writeString(buf, uid)
        IProto.writeString(buf, deviceId)
        IProto.writeString(buf, deviceName)
        IProto.writeString(buf, deviceModel)
        IProto.writeString(buf, clientVersion)
        buf.writeByte(flags.toInt())
    }

    constructor(buf: ByteBuf) : this(
        token = IProto.readString(buf)!!,
        uid = IProto.readString(buf)!!,
        deviceId = IProto.readString(buf)!!,
        deviceName = IProto.readString(buf)!!,
        deviceModel = IProto.readString(buf)!!,
        clientVersion = IProto.readString(buf)!!,
        flags = buf.readByte(),
    )

    companion object {
        val CREATOR = object : IProtoCreator<AuthRequestPayload> {
            override fun create(buf: ByteBuf) = AuthRequestPayload(buf)
        }
        const val ENABLE_TRACE = 0x01
    }

    fun enableTrace(): Boolean {
        return ENABLE_TRACE and flags.toInt() != 0
    }

}


data class AuthResponsePayload(
    val code: Byte,
    val reason: String
) : IProto {

    override val packetType = PacketType.AUTH_RESP
    override fun writeTo(buf: ByteBuf) {
        buf.writeByte(code.toInt())
        IProto.writeString(buf, reason)
    }

    constructor(buf: ByteBuf) : this(
        code = buf.readByte(),
        reason = IProto.readString(buf)!!
    )

    companion object : IProtoCreator<AuthResponsePayload> {
        override fun create(buf: ByteBuf) = AuthResponsePayload(buf)

        // 认证 状态码
        // 成功
        const val CODE_OK: Byte = 0

        // 失败（未分类，具体看reason）
        const val CODE_AUTH_FAILED: Byte = 1

        // 服务器已经不支持当前版本协议，请升级客户端
        const val CODE_VERSION_UNSUPPORTED: Byte = 2

        // 服务器短期维护，请稍等重试
        const val CODE_SERVER_MAINTENANCE: Byte = 3

        // 当前设备被封禁
        const val CODE_DEVICE_BANNED: Byte = 4
    }
}
