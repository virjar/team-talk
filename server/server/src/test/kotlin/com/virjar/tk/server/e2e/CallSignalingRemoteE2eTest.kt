package com.virjar.tk.server.e2e

import com.virjar.tk.protocol.CallEventPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.CallEndReason
import com.virjar.tk.protocol.model.CallEventKind
import com.virjar.tk.protocol.model.CallInviteOutcome
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.rpc.gen.ContactRpcContract
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.util.UUID

/**
 * 1:1 通话信令远程 E2E（协议 minor 0.5）：真实客户端 RPC 直连已部署服务器，
 * 验证 invite→RING→ACCEPTED→signal 中继→hangup→ENDED→CALL_LOG 全链路与离线路径。
 * 与 [RemoteAcceptanceTest] 同门控：-Dtk.e2e.remote=true 才执行（目标 im.virjar.com）。
 */
@EnabledIfSystemProperty(named = "tk.e2e.remote", matches = "true")
class CallSignalingRemoteE2eTest {

    /** 等待匹配的 CALL_EVENT；超时或缓冲中不匹配则继续轮询直至截止。 */
    private suspend fun RemoteAcceptanceSupport.Session.awaitCallEvent(
        kind: CallEventKind,
        callId: String,
        timeoutMs: Long = 10_000,
    ): CallEventPayload {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val notify = runCatching { awaitNotify(NotifyType.CALL_EVENT.code, 1_000) }.getOrNull() ?: continue
            val payload = notify.payload ?: continue
            val decoded = ProtoCodec.decode(CallEventPayload, payload)
            if (decoded.callId == callId && decoded.kind == kind) return decoded
        }
        throw AssertionError("CALL_EVENT($kind) 未到达: callId=$callId")
    }

    @Test
    fun `通话全链路 - 振铃接听中继挂断与通话记录`() = runBlocking {
        val caller = RemoteAcceptanceSupport.registerUser("call-a")
        val callee = RemoteAcceptanceSupport.registerUser("call-b")
        try {
            establishFriendship(caller, callee)

            val callId = UUID.randomUUID().toString()

            // 主叫发起：可达裁决 + TURN ICE 凭据随信令下发
            val inviteResp = caller.invoke(
                "call", 1,
                ProtoCodec.encodePayload { writeString(callId); writeString(callee.uid); writeBoolean(true) },
            )
            assertEquals(0, inviteResp.status, "invite 应成功")
            val outcome = ProtoCodec.decode(CallInviteOutcome, requireNotNull(inviteResp.payload))
            assertTrue(outcome.deliverable && !outcome.busy, "被叫在线应可达")
            assertTrue(
                outcome.iceServers.any { server -> server.urls.any { it.startsWith("turn:") } },
                "部署启用 TURN 时 invite 应携带 turn 凭据: ${outcome.iceServers}",
            )

            // 被叫收到 RING（携带本轮 ICE 服务器）
            val ring = callee.awaitCallEvent(CallEventKind.RING, callId)
            assertEquals(caller.uid, ring.fromUid)
            assertTrue(ring.video)
            assertTrue(ring.iceServers.isNotEmpty())

            // 被叫接听 → 主叫收到 ACCEPTED
            val answerResp = callee.invoke(
                "call", 2,
                ProtoCodec.encodePayload { writeString(callId); writeBoolean(true) },
            )
            assertEquals(0, answerResp.status)
            val accepted = caller.awaitCallEvent(CallEventKind.ACCEPTED, callId)
            assertEquals(callee.uid, accepted.fromUid)

            // 媒体协商中继：主叫 SDP → 被叫
            val fakeSdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=call-e2e"
            val signalResp = caller.invoke(
                "call", 3,
                ProtoCodec.encodePayload {
                    writeString(callId)
                    writeByte(1) // CallSignalBody.TAG_SDP
                    writeBoolean(true)
                    writeString(fakeSdp)
                },
            )
            assertEquals(0, signalResp.status)
            val relayDeadline = System.currentTimeMillis() + 10_000
            var relayedBody: CallSignalBody? = null
            while (relayedBody == null && System.currentTimeMillis() < relayDeadline) {
                val notify = runCatching { callee.awaitNotify(NotifyType.CALL_SIGNAL.code, 1_000) }.getOrNull() ?: continue
                val payload = requireNotNull(notify.payload)
                val decoded = ProtoCodec.decode(com.virjar.tk.protocol.CallSignalPayload, payload)
                if (decoded.callId == callId) relayedBody = decoded.body
            }
            val signal = requireNotNull(relayedBody) { "SDP 中继未到达被叫" }
            assertEquals(fakeSdp, (signal as CallSignalBody.SessionDescription).sdp)

            // 挂断 → 双方 ENDED + CALL_LOG 落库（时长 ≥ 1s，以主叫身份）
            delay(1_200)
            val hangupResp = caller.invoke(
                "call", 4,
                ProtoCodec.encodePayload { writeString(callId); writeVarInt(CallEndReason.HANGUP.code) },
            )
            assertEquals(0, hangupResp.status, "hangup 应成功")
            assertEquals(CallEndReason.HANGUP.code, caller.awaitCallEvent(CallEventKind.ENDED, callId).endReasonCode)
            assertEquals(CallEndReason.HANGUP.code, callee.awaitCallEvent(CallEventKind.ENDED, callId).endReasonCode)

            val log = awaitCallLog(callee, callId)
            assertEquals(CallEndReason.HANGUP.code, log.reasonCode)
            assertTrue(log.durationSec >= 1, "通话时长应 ≥1s，实际 ${log.durationSec}")
        } finally {
            caller.close()
            callee.close()
        }
    }

    @Test
    fun `被叫离线 - 不可达且落未接记录`() = runBlocking {
        val caller = RemoteAcceptanceSupport.registerUser("call-off-a")
        val offlineCallee = RemoteAcceptanceSupport.registerUser("call-off-b")
        try {
            establishFriendship(caller, offlineCallee)
            offlineCallee.close()

            val callId = UUID.randomUUID().toString()
            val inviteResp = caller.invoke(
                "call", 1,
                ProtoCodec.encodePayload { writeString(callId); writeString(offlineCallee.uid); writeBoolean(false) },
            )
            assertEquals(0, inviteResp.status)
            val outcome = ProtoCodec.decode(CallInviteOutcome, requireNotNull(inviteResp.payload))
            assertTrue(!outcome.deliverable && !outcome.busy, "被叫离线应不可达")

            assertEquals(CallEndReason.TIMEOUT.code, caller.awaitCallEvent(CallEventKind.ENDED, callId).endReasonCode)
            val log = awaitCallLog(caller, callId)
            assertEquals(CallEndReason.TIMEOUT.code, log.reasonCode)
            assertEquals(0, log.durationSec)
        } finally {
            runCatching { offlineCallee.close() }
            caller.close()
        }
    }

    @Test
    fun `非参与方信令被拒`() = runBlocking {
        val caller = RemoteAcceptanceSupport.registerUser("call-rx-a")
        val callee = RemoteAcceptanceSupport.registerUser("call-rx-b")
        val outsider = RemoteAcceptanceSupport.registerUser("call-rx-c")
        try {
            establishFriendship(caller, callee)
            val callId = UUID.randomUUID().toString()
            caller.invoke(
                "call", 1,
                ProtoCodec.encodePayload { writeString(callId); writeString(callee.uid); writeBoolean(false) },
            )
            callee.awaitCallEvent(CallEventKind.RING, callId)

            // 陌生第三方挂断别人呼叫 → 业务失败
            val hostile = outsider.invoke(
                "call", 4,
                ProtoCodec.encodePayload { writeString(callId); writeVarInt(CallEndReason.HANGUP.code) },
            )
            assertTrue(hostile.status != 0, "非参与方 hangup 必须被拒绝")

            callee.invoke("call", 2, ProtoCodec.encodePayload { writeString(callId); writeBoolean(false) })
            caller.awaitCallEvent(CallEventKind.ENDED, callId)
        } finally {
            caller.close(); callee.close(); outsider.close()
        }
    }

    private suspend fun establishFriendship(
        applicant: RemoteAcceptanceSupport.Session,
        target: RemoteAcceptanceSupport.Session,
    ) {
        val apply = applicant.invoke(
            "contact", ContactRpcContract.M_APPLY,
            ProtoCodec.encodePayload { writeString(target.uid); writeString("call-e2e") },
        )
        assertEquals(0, apply.status, "好友申请应成功")
        val token = target.pendingApplyToken(applicant.uid)
        val accept = target.invoke(
            "contact", ContactRpcContract.M_ACCEPT,
            ProtoCodec.encodePayload {
                writeString(UUID.randomUUID().toString())
                writeVarLong(System.currentTimeMillis())
                writeString(token)
            },
        )
        assertEquals(0, accept.status, "好友接受应成功")
    }

    /** 从事件投影等 CALL_LOG（messageType 19，clientMsgId=callId）。 */
    private suspend fun awaitCallLog(
        session: RemoteAcceptanceSupport.Session,
        callId: String,
        timeoutMs: Long = 15_000,
    ): com.virjar.tk.protocol.body.CallLogBody {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hit = session.observedMessageEvents().map { it.second }.firstOrNull { msg ->
                msg.messageType == com.virjar.tk.protocol.MessageType.CALL_LOG.code && msg.clientMsgId == callId
            }
            val body = hit?.body as? com.virjar.tk.protocol.body.CallLogBody
            if (body != null) return body
            delay(200)
        }
        throw AssertionError("CALL_LOG 未在 ${timeoutMs}ms 内到达: callId=$callId")
    }
}
