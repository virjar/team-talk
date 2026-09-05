package com.virjar.tk.protocol.payload


import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.ProtocolLimits
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import com.virjar.tk.protocol.telemetry.ConnectionTraceContextPolicy

/**
 * 认证帧的字段级资源预算。
 *
 * 首次认证帧还有 [ProtocolLimits.MAX_UNAUTHENTICATED_PAYLOAD_SIZE] 的整帧围栏；字段预算仍不可省略，因为同一
 * codec 在认证成功后会放开帧上限，恶意客户端可能再次发送 AUTH。这里必须在构造 String
 * 之前拒绝异常声明，不能把大对象交给认证线程池排队。
 */
object AuthPayloadPolicy {
    const val MAX_USERNAME_LENGTH = AuthRules.USERNAME_MAX_LENGTH
    const val MAX_PASSWORD_LENGTH = 256
    const val MAX_NAME_LENGTH = AuthRules.DISPLAY_NAME_MAX_LENGTH
    const val MAX_TOKEN_LENGTH = 256
    const val MAX_DEVICE_ID_LENGTH = AuthRules.DEVICE_ID_MAX_LENGTH
    const val MAX_DEVICE_NAME_LENGTH = AuthRules.DEVICE_METADATA_MAX_LENGTH
    const val MAX_DEVICE_MODEL_LENGTH = AuthRules.DEVICE_METADATA_MAX_LENGTH
    const val MAX_REASON_LENGTH = 1_000
    const val MAX_UID_LENGTH = 64

    private const val MAX_UTF8_BYTES_PER_CHARACTER = 4

    fun utf8WireLimit(maxCharacters: Int): Int {
        require(maxCharacters >= 0 && maxCharacters <= Int.MAX_VALUE / MAX_UTF8_BYTES_PER_CHARACTER)
        return maxCharacters * MAX_UTF8_BYTES_PER_CHARACTER
    }

    fun requireOutboundLength(value: String?, maxCharacters: Int, fieldName: String) {
        if (value != null && value.length > maxCharacters) {
            throw ProtocolEncodingException(
                "$fieldName length ${value.length} exceeds character limit $maxCharacters",
            )
        }
    }

    fun readString(buf: PacketBuffer, maxCharacters: Int, fieldName: String): String? {
        val value = buf.readString(utf8WireLimit(maxCharacters)) ?: return null
        if (value.length > maxCharacters) {
            throw ProtocolCorruptionException(
                "$fieldName length ${value.length} exceeds character limit $maxCharacters",
            )
        }
        return value
    }

    fun readRequiredString(buf: PacketBuffer, maxCharacters: Int, fieldName: String): String {
        val value = buf.readRequiredString(utf8WireLimit(maxCharacters), fieldName)
        if (value.length > maxCharacters) {
            throw ProtocolCorruptionException(
                "$fieldName length ${value.length} exceeds character limit $maxCharacters",
            )
        }
        return value
    }
}

// ── 认证请求 ──

data class AuthRequestPayload(
    val authType: Int,      // 0=login（登录），1=register（注册），2=refresh（刷新）
    val username: String? = null,
    val password: String? = null,
    val name: String? = null,
    val refreshToken: String? = null,
    val deviceId: String,
    val deviceName: String? = null,
    val deviceModel: String? = null,
    val deviceFlag: Int = 0,
    /** 在这条物理连接写入 AUTH 之前立即生成的全新 opaque 标识。 */
    val correlationId: String,
    /** 写入本条 AUTH 的物理 TCP 连接的客户端侧单调标识。 */
    val connectionGeneration: Long,
) : IProto {
    init {
        ConnectionTraceContextPolicy.requireToken(correlationId, "auth.correlationId")
        ConnectionTraceContextPolicy.requirePositive(connectionGeneration, "auth.connectionGeneration")
    }

    /**
     * 认证 payload 可能出现在通用诊断中。保持对象对排查有用，
     * 同时绝不渲染密码或 bearer 凭据。
     */
    override fun toString(): String =
        "AuthRequestPayload(" +
            "authType=$authType, " +
            "deviceId=$deviceId, " +
            "hasUsername=${username != null}, " +
            "hasPassword=${password != null}, " +
            "hasName=${name != null}, " +
            "hasRefreshToken=${refreshToken != null}, " +
            "hasCorrelationId=true, " +
            "connectionGeneration=$connectionGeneration, " +
            "deviceFlag=$deviceFlag" +
            ")"

    /**
     * 连接序言魔（wire 首字段）："TK" + 固定 marker + 固定尾字节。
     * AUTH 在 NEGOTIATE 成功后发送；此固定前缀继续作为认证载荷的错位自检锚点。
     */


    override fun writeTo(buf: PacketBuffer) {
        // 在修改目标 buffer 之前，先校验每个字段专属的字符预算。
        // 通用的 PacketBuffer 上限只是最终的 payload 分配围栏。
        AuthPayloadPolicy.requireOutboundLength(
            username,
            AuthPayloadPolicy.MAX_USERNAME_LENGTH,
            "auth.username",
        )
        AuthPayloadPolicy.requireOutboundLength(
            password,
            AuthPayloadPolicy.MAX_PASSWORD_LENGTH,
            "auth.password",
        )
        AuthPayloadPolicy.requireOutboundLength(name, AuthPayloadPolicy.MAX_NAME_LENGTH, "auth.name")
        AuthPayloadPolicy.requireOutboundLength(
            refreshToken,
            AuthPayloadPolicy.MAX_TOKEN_LENGTH,
            "auth.refreshToken",
        )
        AuthPayloadPolicy.requireOutboundLength(
            deviceId,
            AuthPayloadPolicy.MAX_DEVICE_ID_LENGTH,
            "auth.deviceId",
        )
        AuthPayloadPolicy.requireOutboundLength(
            deviceName,
            AuthPayloadPolicy.MAX_DEVICE_NAME_LENGTH,
            "auth.deviceName",
        )
        AuthPayloadPolicy.requireOutboundLength(
            deviceModel,
            AuthPayloadPolicy.MAX_DEVICE_MODEL_LENGTH,
            "auth.deviceModel",
        )
        try {
            ConnectionTraceContextPolicy.requireToken(correlationId, "auth.correlationId")
            ConnectionTraceContextPolicy.requirePositive(
                connectionGeneration,
                "auth.connectionGeneration",
            )
        } catch (failure: IllegalArgumentException) {
            throw ProtocolEncodingException(failure.message ?: "Invalid AUTH connection identity")
        }
        buf.writeByte(PREAMBLE_HIGH)
        buf.writeByte(PREAMBLE_LOW)
        buf.writeByte(ProtocolLimits.AUTH_PREAMBLE_MARKER.toInt())
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
        buf.writeString(correlationId)
        buf.writeVarLong(connectionGeneration)
    }

    companion object : IProtoReader<AuthRequestPayload> {
        /** 连接序言魔常量："TK" + 固定 marker + 固定尾字节；协议身份已在 NEGOTIATE 确定。 */
        const val PREAMBLE_HIGH: Int = 0x54  // 'T'
        const val PREAMBLE_LOW: Int = 0x4B   // 'K'
        private const val PREAMBLE_TAIL: Int = 0x01

        override fun readFrom(buf: PacketBuffer): AuthRequestPayload {
            // 固定序言只识别错位/畸形输入；它不能被当作业务版本或升级判据。
            val b0 = buf.readByte()
            val b1 = buf.readByte()
            val b2 = buf.readByte()
            val b3 = buf.readByte()
            if (b0 != PREAMBLE_HIGH || b1 != PREAMBLE_LOW || b3 != PREAMBLE_TAIL) {
                throw ProtocolCorruptionException("Bad auth preamble: $b0 $b1 $b2 $b3")
            }
            if (b2 != ProtocolLimits.AUTH_PREAMBLE_MARKER.toInt()) {
                throw ProtocolCorruptionException("Bad auth preamble marker")
            }
            return readBody(buf)
        }

        private fun readBody(buf: PacketBuffer) = AuthRequestPayload(
            authType = buf.readVarInt(),
            username = AuthPayloadPolicy.readString(buf, AuthPayloadPolicy.MAX_USERNAME_LENGTH, "auth.username"),
            password = AuthPayloadPolicy.readString(buf, AuthPayloadPolicy.MAX_PASSWORD_LENGTH, "auth.password"),
            name = AuthPayloadPolicy.readString(buf, AuthPayloadPolicy.MAX_NAME_LENGTH, "auth.name"),
            refreshToken = AuthPayloadPolicy.readString(buf, AuthPayloadPolicy.MAX_TOKEN_LENGTH, "auth.refreshToken"),
            deviceId = AuthPayloadPolicy.readRequiredString(
                buf,
                AuthPayloadPolicy.MAX_DEVICE_ID_LENGTH,
                "auth.deviceId",
            ),
            deviceName = AuthPayloadPolicy.readString(
                buf,
                AuthPayloadPolicy.MAX_DEVICE_NAME_LENGTH,
                "auth.deviceName",
            ),
            deviceModel = AuthPayloadPolicy.readString(
                buf,
                AuthPayloadPolicy.MAX_DEVICE_MODEL_LENGTH,
                "auth.deviceModel",
            ),
            deviceFlag = buf.readVarInt(),
            correlationId = ConnectionTraceContextPolicy.readToken(buf, "auth.correlationId"),
            connectionGeneration = ConnectionTraceContextPolicy.readPositive(
                buf,
                "auth.connectionGeneration",
            ),
        )
    }
}

// ── 认证响应 ──

data class AuthResponsePayload(
    val code: Int,          // 0=成功，1=认证失败，2=版本不支持，3=服务器维护，4=设备被封禁，5=连接数过多
    val reason: String? = null,
    val uid: String? = null,
    val username: String? = null,
    val name: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long = 0,
    /** 每次成功的线格式响应都会出现；内部失败响应中省略。 */
    val datasetId: String? = null,
    /** 针对这条精确成功的物理连接，服务端签发的可选 join key。 */
    val connectionTraceContext: ConnectionTraceContext? = null,
) : IProto {
    init {
        datasetId?.let { SyncDatasetIdPolicy.requireValid(it) }
        require(code == CODE_OK || connectionTraceContext == null) {
            "Failed AUTH response cannot carry a connection trace context"
        }
    }

    /** 绝不在通用诊断中渲染 access 或 refresh 凭据。 */
    override fun toString(): String =
        "AuthResponsePayload(" +
            "code=$code, " +
            "uid=$uid, " +
            "hasAccessToken=${accessToken != null}, " +
            "hasRefreshToken=${refreshToken != null}, " +
            "hasDatasetId=${datasetId != null}, " +
            "hasConnectionTraceContext=${connectionTraceContext != null}, " +
            "expiresIn=$expiresIn" +
            ")"

    override fun writeTo(buf: PacketBuffer) {
        val hasDatasetId = datasetId != null
        if ((code == CODE_OK) != hasDatasetId) {
            throw ProtocolEncodingException(
                "AUTH_RESP dataset identity does not match its result code",
            )
        }
        if (code != CODE_OK && connectionTraceContext != null) {
            throw ProtocolEncodingException(
                "Failed AUTH response cannot carry a connection trace context",
            )
        }
        buf.writeVarInt(code)
        buf.writeString(reason)
        buf.writeString(uid)
        buf.writeString(username)
        buf.writeString(name)
        buf.writeString(accessToken)
        buf.writeString(refreshToken)
        buf.writeVarLong(expiresIn)
        buf.writeString(datasetId)
        buf.writeBoolean(connectionTraceContext != null)
        connectionTraceContext?.writeTo(buf)
    }

    companion object : IProtoReader<AuthResponsePayload> {
        const val CODE_OK = 0
        const val CODE_AUTH_FAILED = 1
        const val CODE_VERSION_UNSUPPORTED = 2
        const val CODE_SERVER_MAINTENANCE = 3
        const val CODE_DEVICE_BANNED = 4
        const val CODE_TOO_MANY_CONNECTIONS = 5

        override fun readFrom(buf: PacketBuffer): AuthResponsePayload {
            val code = buf.readVarInt()
            val reason = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_REASON_LENGTH))
            val uid = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_UID_LENGTH))
            val username = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_USERNAME_LENGTH))
            val name = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_NAME_LENGTH))
            val accessToken = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_TOKEN_LENGTH))
            val refreshToken = buf.readString(AuthPayloadPolicy.utf8WireLimit(AuthPayloadPolicy.MAX_TOKEN_LENGTH))
            val expiresIn = buf.readVarLong()
            val datasetId = SyncDatasetIdPolicy.readOptional(buf, "auth.datasetId")
            val connectionTraceContext = if (buf.readBoolean("auth.connectionTraceContext")) {
                ConnectionTraceContext.readFrom(buf)
            } else {
                null
            }
            if ((code == CODE_OK) != (datasetId != null)) {
                throw ProtocolCorruptionException(
                    "AUTH_RESP dataset identity does not match its result code",
                )
            }
            if (code != CODE_OK && connectionTraceContext != null) {
                throw ProtocolCorruptionException(
                    "Failed AUTH response cannot carry a connection trace context",
                )
            }
            return AuthResponsePayload(
                code = code,
                reason = reason,
                uid = uid,
                username = username,
                name = name,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = expiresIn,
                datasetId = datasetId,
                connectionTraceContext = connectionTraceContext,
            )
        }
    }
}
