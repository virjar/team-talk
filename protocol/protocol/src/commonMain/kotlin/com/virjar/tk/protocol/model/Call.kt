package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol

/**
 * 1:1 通话的共享模型与 wire 编码。
 *
 * 呼叫身份 callId 由主叫客户端生成（UUID 格式），在整条信令链路（RPC/Notify/CALL_LOG）中
 * 唯一标识一次呼叫；服务端与被叫都不重新生成。结束原因在 wire 上统一使用 [CallEndReason.code]，
 * 未知 code 按连接中断收敛，不作为解码失败。
 */

/** 呼叫结束原因。wire 为 Int code；新增值只能追加，已发行 code 不改语义。 */
@SinceProtocol(5)
enum class CallEndReason(val code: Int) {
    /** 主叫在接通前取消。 */
    CANCELLED(1),

    /** 被叫拒接。 */
    DECLINED(2),

    /** 被叫忙线（服务端裁决，被叫无感知）。 */
    BUSY(3),

    /** 振铃超时无人接听。 */
    TIMEOUT(4),

    /** 接通后正常挂断。 */
    HANGUP(5),

    /** 连接中断或进程死亡导致的服务端收敛。 */
    CONNECTION_LOST(6);

    companion object {
        fun fromCode(code: Int): CallEndReason =
            entries.firstOrNull { it.code == code } ?: CONNECTION_LOST
    }
}

/** CALL_EVENT 通知的事件类别。 */
@SinceProtocol(5)
enum class CallEventKind(val code: Int) {
    /** 被叫收到来电振铃。 */
    RING(1),

    /** 主叫收到被叫接听，双方进入媒体协商。 */
    ACCEPTED(2),

    /** 呼叫终结（原因见 [CallEndReason]）。 */
    ENDED(3);

    companion object {
        fun fromCode(code: Int): CallEventKind =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Unknown CallEventKind: $code")
    }
}

/**
 * 服务端随呼叫信令下发的 ICE 服务器。urls 使用标准描述（如 `stun:host:port`、
 * `turn:host:port?transport=udp`）；username/credential 是按呼叫签发的短期凭据，
 * 未启用 TURN 部署时列表为空（仅直连）。
 */
@SinceProtocol(5)
data class IceServer(
    val urls: List<String>,
    val username: String = "",
    val credential: String = "",
) : IProto {
    init {
        require(urls.isNotEmpty() && urls.size <= MAX_URLS) { "ice server 需要至少一个且不超过 $MAX_URLS 个 url" }
        require(urls.all { it.length <= MAX_URL_LENGTH && it.none(Char::isISOControl) }) { "ice url 非法" }
        require(username.length <= MAX_CREDENTIAL_LENGTH && credential.length <= MAX_CREDENTIAL_LENGTH) {
            "ice 凭据超长"
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(urls.size)
        urls.forEach { buf.writeString(it) }
        buf.writeString(username)
        buf.writeString(credential)
    }

    companion object : IProtoReader<IceServer> {
        const val MAX_URLS = 4
        const val MAX_URL_LENGTH = 255
        const val MAX_CREDENTIAL_LENGTH = 128

        override fun readFrom(buf: PacketBuffer): IceServer {
            val count = buf.readCollectionSize(MAX_URLS, minimumBytesPerEntry = 1, fieldName = "ice urls")
            val urls = List(count) { buf.readRequiredString(MAX_URL_LENGTH, "ice url") }
            return IceServer(urls, buf.readString(MAX_CREDENTIAL_LENGTH) ?: "", buf.readString(MAX_CREDENTIAL_LENGTH) ?: "")
        }
    }
}

/** invite 的服务端裁决结果。busy=true 表示对方在通话中（此时不可达）；iceServers 仅在可达时携带。 */
@SinceProtocol(5)
data class CallInviteOutcome(
    val deliverable: Boolean,
    val busy: Boolean,
    val iceServers: List<IceServer>,
) : IProto {
    init {
        if (!deliverable) require(iceServers.isEmpty()) { "不可达裁决不应携带 iceServers" }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeBoolean(deliverable)
        buf.writeBoolean(busy)
        buf.writeVarInt(iceServers.size)
        iceServers.forEach { it.writeTo(buf) }
    }

    companion object : IProtoReader<CallInviteOutcome> {
        override fun readFrom(buf: PacketBuffer): CallInviteOutcome {
            val deliverable = buf.readBoolean("invite deliverable")
            val busy = buf.readBoolean("invite busy")
            val count = buf.readCollectionSize(IceServer.MAX_URLS, minimumBytesPerEntry = 1, fieldName = "iceServers")
            return CallInviteOutcome(deliverable, busy, List(count) { IceServer.readFrom(buf) })
        }
    }
}

/**
 * 媒体协商信令体：SDP 描述（offer/answer，isOffer 区分）与 ICE 候选。
 * SDP 上限 [MAX_SDP_LENGTH] 字符；一期无重协商，一条会话恰好一个 offer 一个 answer。
 */
@SinceProtocol(5)
sealed class CallSignalBody : IProto {
    @SinceProtocol(5)
    data class SessionDescription(val isOffer: Boolean, val sdp: String) : CallSignalBody() {
        init {
            require(sdp.isNotBlank() && sdp.length <= MAX_SDP_LENGTH) { "SDP 为空或超过 $MAX_SDP_LENGTH 字符" }
        }
    }

    @SinceProtocol(5)
    data class IceCandidate(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int) : CallSignalBody() {
        init {
            require(candidate.isNotBlank() && candidate.length <= MAX_CANDIDATE_LENGTH) { "ICE candidate 非法" }
            require(sdpMid.length <= 16) { "sdpMid 非法" }
            require(sdpMLineIndex in 0..MAX_M_LINE_INDEX) { "sdpMLineIndex 越界" }
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        when (this) {
            is SessionDescription -> {
                buf.writeByte(TAG_SDP)
                buf.writeBoolean(isOffer)
                buf.writeString(sdp)
            }
            is IceCandidate -> {
                buf.writeByte(TAG_ICE)
                buf.writeString(candidate)
                buf.writeString(sdpMid)
                buf.writeVarInt(sdpMLineIndex)
            }
        }
    }

    companion object : IProtoReader<CallSignalBody> {
        const val MAX_SDP_LENGTH = 32 * 1024
        const val MAX_CANDIDATE_LENGTH = 512
        const val MAX_M_LINE_INDEX = 16
        private const val TAG_SDP = 1
        private const val TAG_ICE = 2

        override fun readFrom(buf: PacketBuffer): CallSignalBody =
            when (val tag = buf.readByte()) {
                TAG_SDP -> SessionDescription(buf.readBoolean("sdp offer"), buf.readRequiredString(MAX_SDP_LENGTH, "sdp"))
                TAG_ICE -> IceCandidate(
                    buf.readRequiredString(MAX_CANDIDATE_LENGTH, "candidate"),
                    buf.readString(16) ?: "",
                    buf.readVarInt(),
                )
                else -> throw IllegalArgumentException("Unknown CallSignalBody tag: $tag")
            }
    }
}
