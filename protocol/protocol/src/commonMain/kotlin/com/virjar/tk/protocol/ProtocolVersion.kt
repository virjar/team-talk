package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.ProtocolNegotiateResponsePayload

/** 独立于应用发行字符串的二进制契约身份；同 major 内 minor 只追加契约。 */
data class ProtocolVersion(val major: Int, val minor: Int) : Comparable<ProtocolVersion>, IProto {
    init {
        require(major in 0..32767) { "protocol major must be in 0..32767" }
        require(minor in 0..65535) { "protocol minor must be in 0..65535" }
    }

    val id: Int get() = (major shl 16) or minor
    override fun compareTo(other: ProtocolVersion): Int = id.compareTo(other.id)
    override fun toString(): String = "$major.$minor"
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(major)
        buf.writeVarInt(minor)
    }

    companion object : IProtoReader<ProtocolVersion> {
        fun fromId(id: Int): ProtocolVersion {
            require(id >= 0) { "protocol id must be nonnegative" }
            return ProtocolVersion(id ushr 16, id and 65535)
        }
        override fun readFrom(buf: PacketBuffer): ProtocolVersion = ProtocolVersion(buf.readVarInt(), buf.readVarInt())
    }
}

/** 一个进程实际保留的同 major 实现窗口；不是服务端替客户端猜测的能力集合。 */
data class ProtocolRange(val major: Int, val minimumMinor: Int, val currentMinor: Int) : IProto {
    init {
        ProtocolVersion(major, minimumMinor)
        ProtocolVersion(major, currentMinor)
        require(minimumMinor <= currentMinor) { "protocol minimum must not exceed current" }
    }

    val current: ProtocolVersion get() = ProtocolVersion(major, currentMinor)
    val minimum: ProtocolVersion get() = ProtocolVersion(major, minimumMinor)
    operator fun contains(version: ProtocolVersion): Boolean =
        version.major == major && version.minor in minimumMinor..currentMinor

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(major)
        buf.writeVarInt(minimumMinor)
        buf.writeVarInt(currentMinor)
    }

    companion object : IProtoReader<ProtocolRange> {
        override fun readFrom(buf: PacketBuffer): ProtocolRange =
            ProtocolRange(buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
    }
}

/** 版本窗口算法由握手双方复用；客户端必须验证响应后才发送任何凭据。 */
object ProtocolNegotiation {
    fun negotiate(
        client: ProtocolRange,
        server: ProtocolRange,
        serverReleaseVersion: String = "0.0.0",
    ): ProtocolNegotiateResponsePayload {
        val code = when {
            client.major != server.major -> ProtocolNegotiateResponsePayload.CODE_MAJOR_UNSUPPORTED
            client.currentMinor < server.minimumMinor -> ProtocolNegotiateResponsePayload.CODE_CLIENT_TOO_OLD
            server.currentMinor < client.minimumMinor -> ProtocolNegotiateResponsePayload.CODE_SERVER_TOO_OLD
            else -> ProtocolNegotiateResponsePayload.CODE_OK
        }
        val negotiated = if (code == ProtocolNegotiateResponsePayload.CODE_OK) {
            ProtocolVersion(server.major, minOf(client.currentMinor, server.currentMinor))
        } else null
        return ProtocolNegotiateResponsePayload(code, server, negotiated, serverReleaseVersion)
    }

    fun requireValidResponse(client: ProtocolRange, response: ProtocolNegotiateResponsePayload) {
        require(response == negotiate(client, response.server, response.serverReleaseVersion)) {
            "protocol negotiation response does not match the offer"
        }
    }
}

/** 注解版本均是当前 major 内的 minor；跨 major 的基线由独立版本常量和清单重建。 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
annotation class SinceProtocol(val minor: Int)

/** 在该 minor 起不再可调用；最低版本越过此点后必须删除实现，并保留本 major 的 ID 墓碑。 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
annotation class RemovedInProtocol(val minor: Int)

/** 构造参数属于本地状态，不进入 wire 类型签名；不可用于跳过真正的编码字段。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class ProtocolLocal

data class ProtocolAvailability(val sinceMinor: Int, val removedInMinor: Int? = null) {
    fun supports(version: ProtocolVersion): Boolean =
        version.major == ProtocolVersions.MAJOR && version.minor >= sinceMinor &&
            (removedInMinor == null || version.minor < removedInMinor)
}
