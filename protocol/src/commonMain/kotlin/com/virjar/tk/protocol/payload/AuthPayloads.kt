package com.virjar.tk.protocol.payload


import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketCodec
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
    val lastEventId: Long = 0,
) : IProto {

    /**
     * 连接序言魔（wire 首字段）："TK" + PROTOCOL_VERSION + 固定尾字节。
     * 首帧 AUTH 是连接的必经包——首字节特征让端口扫描/误连流量在 payload
     * 第一字节即被拒（复刻 HTTP/2 连接序言思路），并保留错位自检锚点。
     */


    override fun writeTo(buf: PacketBuffer) {
        buf.writeByte(PREAMBLE_HIGH)
        buf.writeByte(PREAMBLE_LOW)
        buf.writeByte(PacketCodec.PROTOCOL_VERSION.toInt())
        buf.writeByte(PREAMBLE_TAIL)
        buf.writeVarInt(authType)
        buf.writeString(username)
        buf.writeString(password)
        buf.writeString(name)
        buf.writeString(refreshToken)
        buf.writeString(deviceId)
        buf.writeString(deviceName)
        buf.writeString(deviceModel)
        buf.writeVarInt(deviceFlag)
        buf.writeVarLong(lastEventId)
    }

    companion object : IProtoReader<AuthRequestPayload> {
        /** 连接序言魔常量："TK" + PROTOCOL_VERSION + 固定尾字节 */
        const val PREAMBLE_HIGH: Int = 0x54  // 'T'
        const val PREAMBLE_LOW: Int = 0x4B   // 'K'
        private const val PREAMBLE_TAIL: Int = 0x01

        override fun readFrom(buf: PacketBuffer): AuthRequestPayload {
            // 序言魔校验：不匹配 = 非协议流量/错位 → 抛异常经 codec 异常路径断连
            val b0 = buf.readByte()
            val b1 = buf.readByte()
            val b2 = buf.readByte()
            val b3 = buf.readByte()
            if (b0 != PREAMBLE_HIGH || b1 != PREAMBLE_LOW || b2 != PacketCodec.PROTOCOL_VERSION.toInt() || b3 != PREAMBLE_TAIL) {
                throw IllegalStateException(
                    "Bad auth preamble: ${b0.toInt() and 0xFF} ${b1.toInt() and 0xFF} ${b2.toInt() and 0xFF} ${b3.toInt() and 0xFF}")
            }
            return readBody(buf)
        }

        private fun readBody(buf: PacketBuffer) = AuthRequestPayload(
            authType = buf.readVarInt(),
            username = buf.readString(),
            password = buf.readString(),
            name = buf.readString(),
            refreshToken = buf.readString(),
            deviceId = buf.readString()!!,
            deviceName = buf.readString(),
            deviceModel = buf.readString(),
            deviceFlag = buf.readVarInt(),
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
