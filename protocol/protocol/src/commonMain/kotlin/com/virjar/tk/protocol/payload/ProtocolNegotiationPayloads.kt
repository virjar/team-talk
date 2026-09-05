package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolRange
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.ProtocolVersions

/** 固定 bootstrap 信封；其字段不能随业务 minor 改写，协商前不携带认证凭据。 */
data class ProtocolNegotiateRequestPayload(
    val supported: ProtocolRange = ProtocolVersions.SUPPORTED,
    val clientReleaseVersion: String = "0.0.0",
) : IProto {
    init { requireReleaseVersion(clientReleaseVersion) }
    override fun writeTo(buf: PacketBuffer) {
        supported.writeTo(buf)
        buf.writeString(clientReleaseVersion)
    }

    companion object : IProtoReader<ProtocolNegotiateRequestPayload> {
        override fun readFrom(buf: PacketBuffer): ProtocolNegotiateRequestPayload =
            ProtocolNegotiateRequestPayload(ProtocolRange.readFrom(buf), buf.readRequiredString(64, "clientReleaseVersion"))
    }
}

/** 成功时恰好携带一个协商结果，拒绝时仅返回服务端实际支持窗口。 */
data class ProtocolNegotiateResponsePayload(
    val code: Int,
    val server: ProtocolRange,
    val negotiated: ProtocolVersion? = null,
    val serverReleaseVersion: String = "0.0.0",
) : IProto {
    init {
        requireReleaseVersion(serverReleaseVersion)
        require(code in CODE_OK..CODE_SERVER_TOO_OLD) { "unknown protocol negotiation result" }
        require((code == CODE_OK) == (negotiated != null)) { "negotiated version does not match result" }
        require(negotiated == null || negotiated in server) { "negotiated version is outside server window" }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(code)
        server.writeTo(buf)
        buf.writeBoolean(negotiated != null)
        negotiated?.writeTo(buf)
        buf.writeString(serverReleaseVersion)
    }

    companion object : IProtoReader<ProtocolNegotiateResponsePayload> {
        const val CODE_OK = 0
        const val CODE_MAJOR_UNSUPPORTED = 1
        const val CODE_CLIENT_TOO_OLD = 2
        const val CODE_SERVER_TOO_OLD = 3

        override fun readFrom(buf: PacketBuffer): ProtocolNegotiateResponsePayload =
            ProtocolNegotiateResponsePayload(
                code = buf.readVarInt(),
                server = ProtocolRange.readFrom(buf),
                negotiated = if (buf.readBoolean("negotiation.version")) ProtocolVersion.readFrom(buf) else null,
                serverReleaseVersion = buf.readRequiredString(64, "serverReleaseVersion"),
            )
    }
}

private fun requireReleaseVersion(value: String) {
    require(value.length in 5..64 && value.matches(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))) {
        "release version must be a canonical three-component numeric version"
    }
}
