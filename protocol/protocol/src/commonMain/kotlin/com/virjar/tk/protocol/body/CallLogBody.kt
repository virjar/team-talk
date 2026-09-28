package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.model.CallEndReason

/**
 * 通话记录：服务端在呼叫终结时以系统身份写入双方私聊会话，进普通历史与离线推送。
 * 未接通时 durationSec=0；reason 为 [CallEndReason.code]，未知值读侧按 CONNECTION_LOST 收敛，
 * 不因版本差异拒绝整条消息。
 */
@SinceProtocol(5)
data class CallLogBody(
    val callId: String,
    val callerUid: String,
    val calleeUid: String,
    val video: Boolean,
    val durationSec: Int,
    val reasonCode: Int,
) : MessageBody {
    val reason: CallEndReason get() = CallEndReason.fromCode(reasonCode)

    init {
        CallLogPolicy.requireId(callId)
        require(callerUid.isNotBlank() && calleeUid.isNotBlank() && callerUid != calleeUid) { "通话双方非法" }
        require(callerUid.length <= CallLogPolicy.MAX_UID_LENGTH && calleeUid.length <= CallLogPolicy.MAX_UID_LENGTH) {
            "通话双方 uid 超长"
        }
        require(durationSec in 0..CallLogPolicy.MAX_DURATION_SEC) { "通话时长越界" }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(callId)
        buf.writeString(callerUid)
        buf.writeString(calleeUid)
        buf.writeBoolean(video)
        buf.writeVarInt(durationSec)
        buf.writeVarInt(reasonCode)
    }

    companion object : IProtoReader<CallLogBody> {
        override fun readFrom(buf: PacketBuffer): CallLogBody = CallLogBody(
            buf.readRequiredString(CallLogPolicy.MAX_ID_LENGTH, "callId"),
            buf.readRequiredString(CallLogPolicy.MAX_UID_LENGTH, "callerUid"),
            buf.readRequiredString(CallLogPolicy.MAX_UID_LENGTH, "calleeUid"),
            buf.readBoolean("call log video"),
            buf.readVarInt(),
            buf.readVarInt(),
        )
    }
}

object CallLogPolicy {
    const val MAX_ID_LENGTH = 36
    const val MAX_UID_LENGTH = 64
    const val MAX_DURATION_SEC = 24 * 60 * 60

    fun requireId(callId: String) {
        require(callId.isNotBlank() && callId.length <= MAX_ID_LENGTH && callId.none(Char::isISOControl)) {
            "callId 非法"
        }
    }
}
