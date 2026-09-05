package com.virjar.tk.protocol

/**
 * 在线状态通知载荷（PRESENCE）：好友上下线广播。
 * 服务端直写不持久化（在线状态无补发价值）；客户端经契约表解码。
 */
data class PresencePayload(
    val uid: String,
    val status: Byte,
    val lastSeenAt: Long,
    val serverEpoch: String,
    val revision: Long,
) : IProto {
    init {
        PresenceContractPolicy.requireUid(uid, "presence.uid")
        require(status == STATUS_OFFLINE || status == STATUS_ONLINE) { "Invalid presence status: $status" }
        require(lastSeenAt >= 0L) { "presence.lastSeenAt must be non-negative" }
        PresenceContractPolicy.requireServerEpoch(serverEpoch)
        require(revision > 0L) { "presence.revision must be positive" }
        if (status == STATUS_ONLINE) {
            require(lastSeenAt == 0L) { "Online presence must use lastSeenAt=0" }
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(serverEpoch)
        buf.writeVarLong(revision)
        buf.writeString(uid)
        buf.writeByte(status.toInt())
        buf.writeVarLong(lastSeenAt)
    }

    companion object : IProtoReader<PresencePayload> {
        const val STATUS_OFFLINE: Byte = 0
        const val STATUS_ONLINE: Byte = 1

        override fun readFrom(buf: PacketBuffer): PresencePayload {
            val serverEpoch = PresenceContractPolicy.readServerEpoch(buf, "presence.serverEpoch")
            val revision = buf.readVarLong()
            val uid = PresenceContractPolicy.readUid(buf, "presence.uid")
            val status = buf.readByte().toByte()
            val lastSeenAt = buf.readVarLong()
            return decodePresenceValue {
                PresencePayload(
                    uid = uid,
                    status = status,
                    lastSeenAt = lastSeenAt,
                    serverEpoch = serverEpoch,
                    revision = revision,
                )
            }
        }
    }
}

/** 版本化 presence 快照与增量的共享 canonical 边界。 */
object PresenceContractPolicy {
    const val MAX_FRIENDS_PER_SNAPSHOT = 4_000
    const val MAX_UID_LENGTH = 64
    const val SERVER_EPOCH_LENGTH = 36

    private const val MAX_UTF8_BYTES_PER_CHARACTER = 4
    private val CANONICAL_UUID = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
    )

    fun requireServerEpoch(value: String) {
        require(value.length == SERVER_EPOCH_LENGTH && CANONICAL_UUID.matches(value)) {
            "serverEpoch must be a canonical UUID"
        }
    }

    fun requireUid(value: String, fieldName: String) {
        require(value.isNotBlank() && value.length <= MAX_UID_LENGTH) {
            "$fieldName must contain 1..$MAX_UID_LENGTH characters"
        }
        require(value.none { it.isISOControl() }) { "$fieldName contains a control character" }
    }

    fun readServerEpoch(buf: PacketBuffer, fieldName: String): String {
        val value = buf.readRequiredString(SERVER_EPOCH_LENGTH, fieldName)
        try {
            requireServerEpoch(value)
        } catch (invalid: IllegalArgumentException) {
            throw ProtocolCorruptionException(invalid.message ?: "$fieldName is invalid")
        }
        return value
    }

    fun readUid(buf: PacketBuffer, fieldName: String): String {
        val value = buf.readRequiredString(MAX_UID_LENGTH * MAX_UTF8_BYTES_PER_CHARACTER, fieldName)
        try {
            requireUid(value, fieldName)
        } catch (invalid: IllegalArgumentException) {
            throw ProtocolCorruptionException(invalid.message ?: "$fieldName is invalid")
        }
        return value
    }
}

private inline fun <T> decodePresenceValue(build: () -> T): T = try {
    build()
} catch (corrupt: ProtocolCorruptionException) {
    throw corrupt
} catch (invalid: IllegalArgumentException) {
    throw ProtocolCorruptionException(invalid.message ?: "Invalid presence payload")
}
