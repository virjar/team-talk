package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.CallEndReason
import com.virjar.tk.protocol.model.CallEventKind
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.model.IceServer

/**
 * 通话瞬时信令 payload（eventId=0，不持久化、离线不补偿）。
 *
 * 通话的存在性由两端进程内 [com.virjar.tk.protocol.model.CallLogBody] 记录与在线状态承载，
 * 信令本身丢失即呼叫失败，双方以超时收敛——这是有意设计，不进入可靠事件流。
 */

/**
 * 呼叫事件：RING 只投给被叫（携带本轮呼叫的 ICE 服务器），ACCEPTED 只投给主叫，
 * ENDED 投给对端（reason 使用 [CallEndReason.code]）。durationSec 仅在服务端
 * 落 CALL_LOG 时计算，payload 不携带。
 */
@SinceProtocol(5)
data class CallEventPayload(
    val callId: String,
    val fromUid: String,
    val kind: CallEventKind,
    val video: Boolean,
    val endReasonCode: Int,
    val iceServers: List<IceServer>,
) : IProto {
    init {
        requireCallId(callId)
        require(fromUid.isNotBlank() && fromUid.length <= MAX_CALL_UID_LENGTH) { "fromUid 非法" }
        if (kind != CallEventKind.RING) require(iceServers.isEmpty()) { "仅 RING 携带 iceServers" }
        if (kind != CallEventKind.ENDED) require(endReasonCode == 0) { "仅 ENDED 携带结束原因" }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(callId)
        buf.writeString(fromUid)
        buf.writeByte(kind.code)
        buf.writeBoolean(video)
        buf.writeVarInt(endReasonCode)
        buf.writeVarInt(iceServers.size)
        iceServers.forEach { it.writeTo(buf) }
    }

    companion object : IProtoReader<CallEventPayload> {
        const val MAX_CALL_ID_LENGTH = 36
        const val MAX_CALL_UID_LENGTH = 64

        fun requireCallId(callId: String) {
            require(callId.isNotBlank() && callId.length <= MAX_CALL_ID_LENGTH && callId.none(Char::isISOControl)) {
                "callId 非法"
            }
        }

        override fun readFrom(buf: PacketBuffer): CallEventPayload {
            val callId = buf.readRequiredString(MAX_CALL_ID_LENGTH, "callId")
            val fromUid = buf.readRequiredString(MAX_CALL_UID_LENGTH, "call fromUid")
            val kind = CallEventKind.fromCode(buf.readByte())
            val video = buf.readBoolean("call video")
            val endReasonCode = buf.readVarInt()
            val count = buf.readCollectionSize(IceServer.MAX_URLS, minimumBytesPerEntry = 1, fieldName = "call iceServers")
            return CallEventPayload(callId, fromUid, kind, video, endReasonCode, List(count) { IceServer.readFrom(buf) })
        }
    }
}

/** 媒体协商中继：服务端按呼叫表把 body 原样转发给对端，不理解内容。 */
@SinceProtocol(5)
data class CallSignalPayload(
    val callId: String,
    val body: CallSignalBody,
) : IProto {
    init { CallEventPayload.requireCallId(callId) }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(callId)
        body.writeTo(buf)
    }

    companion object : IProtoReader<CallSignalPayload> {
        override fun readFrom(buf: PacketBuffer): CallSignalPayload =
            CallSignalPayload(buf.readRequiredString(CallEventPayload.MAX_CALL_ID_LENGTH, "callId"), CallSignalBody.readFrom(buf))
    }
}
