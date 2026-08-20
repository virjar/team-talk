package com.virjar.tk.protocol.payload


import com.virjar.tk.auth.AuthRules
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketCodec
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolVersionMismatchException
import io.netty.handler.codec.CorruptedFrameException

/**
 * 认证帧的字段级资源预算。
 *
 * 首次认证帧还有 [PacketCodec.UNAUTHED_LIMIT] 的整帧围栏；字段预算仍不可省略，因为同一
 * codec 在认证成功后会放开帧上限，恶意客户端可能再次发送 AUTH。这里必须在构造 String
 * 之前拒绝异常声明，不能把大对象交给认证线程池排队。
 */
object AuthPayloadPolicy {
    const val MAX_USERNAME_LENGTH = AuthRules.USERNAME_MAX_LENGTH
    const val MAX_PASSWORD_LENGTH = 256
    const val MAX_NAME_LENGTH = 100
    const val MAX_TOKEN_LENGTH = 256
    const val MAX_DEVICE_ID_LENGTH = 100
    const val MAX_DEVICE_NAME_LENGTH = 200
    const val MAX_DEVICE_MODEL_LENGTH = 200
    const val MAX_REASON_LENGTH = 1_000
    const val MAX_UID_LENGTH = 64

    private const val MAX_UTF8_BYTES_PER_CHARACTER = 4

    fun utf8WireLimit(maxCharacters: Int): Int {
        require(maxCharacters >= 0 && maxCharacters <= Int.MAX_VALUE / MAX_UTF8_BYTES_PER_CHARACTER)
        return maxCharacters * MAX_UTF8_BYTES_PER_CHARACTER
    }
}

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
            // 只有魔数和尾字节都有效时，版本字节才是可信的升级判据。随机流量、错位和
            // 畸形帧继续走普通 codec 断连，绝不能被客户端误报成“必须升级”。
            val b0 = buf.readByte()
            val b1 = buf.readByte()
            val b2 = buf.readByte()
            val b3 = buf.readByte()
            if (b0 != PREAMBLE_HIGH || b1 != PREAMBLE_LOW || b3 != PREAMBLE_TAIL) {
                throw CorruptedFrameException("Bad auth preamble: $b0 $b1 $b2 $b3")
            }
            val supportedVersion = PacketCodec.PROTOCOL_VERSION.toInt() and 0xFF
            if (b2 != supportedVersion) {
                throw ProtocolVersionMismatchException(
                    receivedVersion = b2,
                    supportedVersion = supportedVersion,
                )
            }
            return readBody(buf)
        }

        private fun readBody(buf: PacketBuffer) = AuthRequestPayload(
            authType = buf.readVarInt(),
            username = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_USERNAME_LENGTH)),
            password = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_PASSWORD_LENGTH)),
            name = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_NAME_LENGTH)),
            refreshToken = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_TOKEN_LENGTH)),
            deviceId = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_DEVICE_ID_LENGTH))!!,
            deviceName = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_DEVICE_NAME_LENGTH)),
            deviceModel = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_DEVICE_MODEL_LENGTH)),
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
        const val CODE_OK = 0
        const val CODE_AUTH_FAILED = 1
        const val CODE_VERSION_UNSUPPORTED = 2
        const val CODE_SERVER_MAINTENANCE = 3
        const val CODE_DEVICE_BANNED = 4
        const val CODE_TOO_MANY_CONNECTIONS = 5

        override fun readFrom(buf: PacketBuffer) = AuthResponsePayload(
            code = buf.readVarInt(),
            reason = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_REASON_LENGTH)),
            uid = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_UID_LENGTH)),
            username = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_USERNAME_LENGTH)),
            name = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_NAME_LENGTH)),
            accessToken = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_TOKEN_LENGTH)),
            refreshToken = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_TOKEN_LENGTH)),
            expiresIn = buf.readVarLong(),
        )
    }
}
