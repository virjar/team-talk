package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.CallEndReason
import com.virjar.tk.protocol.model.CallInviteOutcome
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.model.IceServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 通话信令契约：round-trip、边界拒绝与未知值收敛。 */
class CallContractTest {

    private val callId = "01234567-89ab-4cde-8f01-23456789abcd"

    @Test
    fun `信令体 round-trip`() {
        listOf(
            CallSignalBody.SessionDescription(isOffer = true, sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0"),
            CallSignalBody.SessionDescription(isOffer = false, sdp = "v=0\r\n...answer"),
            CallSignalBody.IceCandidate("candidate:1 1 UDP 2130706431 10.0.0.1 8998 typ host", "0", 0),
            CallSignalBody.IceCandidate("candidate:2 1 UDP 1694498815 192.168.0.1 51001 typ srflx", "1", 2),
        ).forEach { value ->
            assertEquals(value, ProtoCodec.decode(CallSignalBody, ProtoCodec.encode(value)))
        }
    }

    @Test
    fun `payload round-trip`() {
        val ring = CallEventPayload(
            callId = callId, fromUid = "u1", kind = com.virjar.tk.protocol.model.CallEventKind.RING,
            video = true, endReasonCode = 0,
            iceServers = listOf(
                IceServer(listOf("stun:example.com:3478")),
                IceServer(listOf("turn:example.com:3478?transport=udp"), "1234-5678", "hmaccred"),
            ),
        )
        assertEquals(ring, ProtoCodec.decode(CallEventPayload, ProtoCodec.encode(ring)))

        val accepted = CallEventPayload(
            callId = callId, fromUid = "u2", kind = com.virjar.tk.protocol.model.CallEventKind.ACCEPTED,
            video = false, endReasonCode = 0, iceServers = emptyList(),
        )
        assertEquals(accepted, ProtoCodec.decode(CallEventPayload, ProtoCodec.encode(accepted)))

        val ended = CallEventPayload(
            callId = callId, fromUid = "u1", kind = com.virjar.tk.protocol.model.CallEventKind.ENDED,
            video = false, endReasonCode = CallEndReason.HANGUP.code, iceServers = emptyList(),
        )
        assertEquals(ended, ProtoCodec.decode(CallEventPayload, ProtoCodec.encode(ended)))

        val signal = CallSignalPayload(callId, CallSignalBody.IceCandidate("candidate:x", "0", 1))
        assertEquals(signal, ProtoCodec.decode(CallSignalPayload, ProtoCodec.encode(signal)))
    }

    @Test
    fun `invite 结果与未知结束原因收敛`() {
        val outcome = CallInviteOutcome(
            deliverable = true, busy = false,
            iceServers = listOf(IceServer(listOf("turn:example.com:3478?transport=udp"), "u", "c")),
        )
        assertEquals(outcome, ProtoCodec.decode(CallInviteOutcome, ProtoCodec.encode(outcome)))

        val offline = CallInviteOutcome(deliverable = false, busy = false, iceServers = emptyList())
        assertEquals(offline, ProtoCodec.decode(CallInviteOutcome, ProtoCodec.encode(offline)))

        // 忙线被叫：不可达但携带忙线标记，不给 ICE 凭据
        val busyPeer = CallInviteOutcome(deliverable = false, busy = true, iceServers = emptyList())
        assertEquals(busyPeer, ProtoCodec.decode(CallInviteOutcome, ProtoCodec.encode(busyPeer)))

        assertEquals(CallEndReason.CONNECTION_LOST, CallEndReason.fromCode(999))
        assertEquals(CallEndReason.DECLINED, CallEndReason.fromCode(CallEndReason.DECLINED.code))
    }

    @Test
    fun `边界拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            CallSignalBody.SessionDescription(true, " ")
        }
        assertFailsWith<IllegalArgumentException> {
            CallSignalBody.SessionDescription(true, "x".repeat(CallSignalBody.MAX_SDP_LENGTH + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            CallSignalBody.IceCandidate("c".repeat(CallSignalBody.MAX_CANDIDATE_LENGTH + 1), "0", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CallSignalBody.IceCandidate("ok", "0", CallSignalBody.MAX_M_LINE_INDEX + 1)
        }
        assertFailsWith<IllegalArgumentException> { CallEventPayload("bad\u0001", "u1", com.virjar.tk.protocol.model.CallEventKind.RING, false, 0, emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            CallEventPayload(callId, "u1", com.virjar.tk.protocol.model.CallEventKind.ACCEPTED, false, 0, listOf(IceServer(listOf("stun:x:1"))))
        }
        assertFailsWith<IllegalArgumentException> { IceServer(emptyList()) }
    }

    @Test
    fun `未知信令 tag 拒绝解码`() {
        val buf = PacketBuffer()
        buf.writeByte(99)
        assertTrue(
            runCatching { CallSignalBody.readFrom(buf) }.isFailure,
            "未知 tag 必须显式失败而不是静默吞掉",
        )
    }

    @Test
    fun `CALL_LOG body round-trip 与未知原因收敛`() {
        val connected = com.virjar.tk.protocol.body.CallLogBody(
            callId = callId, callerUid = "u1", calleeUid = "u2",
            video = true, durationSec = 75, reasonCode = CallEndReason.HANGUP.code,
        )
        val encoded = PacketBuffer().also { connected.writeTo(it) }.toByteArray()
        assertEquals(connected, com.virjar.tk.protocol.body.MessageBodyRegistry.decode(MessageType.CALL_LOG, PacketBuffer(encoded)))

        val unknownReason = com.virjar.tk.protocol.body.CallLogBody(
            callId = callId, callerUid = "u1", calleeUid = "u2",
            video = false, durationSec = 0, reasonCode = 999,
        )
        val decoded = com.virjar.tk.protocol.body.MessageBodyRegistry.decode(
            MessageType.CALL_LOG,
            PacketBuffer(PacketBuffer().also { unknownReason.writeTo(it) }.toByteArray()),
        ) as com.virjar.tk.protocol.body.CallLogBody
        assertEquals(999, decoded.reasonCode)
        assertEquals(CallEndReason.CONNECTION_LOST, decoded.reason)
        // 旧客户端兼容路径：未知 MessageType 安全跳过
        assertEquals(null, com.virjar.tk.protocol.body.MessageBodyRegistry.decode(MessageType.fromCode(999), PacketBuffer(encoded)))
    }
}
