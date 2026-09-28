package com.virjar.tk.server.domain.call

import com.virjar.tk.protocol.CallEventPayload
import com.virjar.tk.protocol.CallSignalPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.model.CallEndReason
import com.virjar.tk.protocol.model.CallEventKind
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.model.IceServer
import com.virjar.tk.server.domain.event.TransientEventPublisher
import com.virjar.tk.server.domain.presence.PresenceTransition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 呼叫状态机行为：准入、忙线、离线、应答、中继、终结与超时（纯假依赖，无 IO）。 */
class CallServiceTest {

    private data class Record(
        val chatId: String,
        val callId: String,
        val callerUid: String,
        val calleeUid: String,
        val video: Boolean,
        val durationSec: Int,
        val reasonCode: Int,
    )

    private class Fixture(
        friends: Set<Pair<String, String>> = setOf("u1" to "u2", "u2" to "u1"),
        online: Set<String> = setOf("u1", "u2"),
    ) {
        val sent = mutableListOf<Triple<String, NotifyType, com.virjar.tk.protocol.IProto>>()
        val logs = mutableListOf<Record>()
        var now = 1_000_000L

        val service = CallService(
            admission = CallAdmission { caller, callee -> (caller to callee) in friends },
            sessions = CallChatSessions { caller, callee -> "chat-$caller-$callee" },
            callLog = CallLogSink { chatId, callId, callerUid, calleeUid, video, durationSec, reasonCode ->
                logs += Record(chatId, callId, callerUid, calleeUid, video, durationSec, reasonCode)
            },
            publisher = object : TransientEventPublisher {
                override suspend fun emitTransient(uid: String, notifyType: NotifyType, payload: com.virjar.tk.protocol.IProto) {
                    sent += Triple(uid, notifyType, payload)
                }
            },
            onlineChecker = CallOnlineChecker { it in online },
            iceServers = CallIceServers { validity ->
                assertTrue(validity > 0)
                listOf(IceServer(listOf("turn:example.com:3478?transport=udp"), "u", "c"))
            },
            nowMillis = { now },
        )

        fun callEvents(uid: String, kind: CallEventKind): List<CallEventPayload> =
            sent.filter { it.first == uid && it.second == NotifyType.CALL_EVENT }
                .map { it.third as CallEventPayload }
                .filter { it.kind == kind }

        fun advanceMillis(millis: Long) {
            now += millis
        }
    }

    private fun transitionOf(uid: String, online: Boolean) = PresenceTransition(
        uid = uid,
        online = online,
        occurredAt = 0L,
        serverEpoch = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        revision = 1L,
    )

    @Test
    fun `非好友发起被拒绝`() = runTest {
        val f = Fixture(friends = emptySet())
        val error = assertFailsWith<IllegalArgumentException> {
            f.service.invite("u1", "call-1", "u2", video = false)
        }
        assertTrue(error.message!!.contains("好友"))
        assertTrue(f.sent.isEmpty())
        assertTrue(f.logs.isEmpty())
    }

    @Test
    fun `在线振铃 - 被叫收到主叫与凭据`() = runTest {
        val f = Fixture()
        val outcome = f.service.invite("u1", "call-1", "u2", video = true)
        assertTrue(outcome.deliverable)
        assertFalse(outcome.busy)
        assertEquals(1, outcome.iceServers.size)
        val ring = f.callEvents("u2", CallEventKind.RING).single()
        assertEquals("u1", ring.fromUid)
        assertTrue(ring.video)
        assertEquals(1, ring.iceServers.size)
        assertTrue(f.callEvents("u1", CallEventKind.RING).isEmpty())
    }

    @Test
    fun `离线被叫 - 不可达并落未接记录`() = runTest {
        val f = Fixture(online = setOf("u1"))
        val outcome = f.service.invite("u1", "call-1", "u2", video = false)
        assertFalse(outcome.deliverable)
        assertFalse(outcome.busy)
        assertTrue(outcome.iceServers.isEmpty())
        val record = f.logs.single()
        assertEquals(CallEndReason.TIMEOUT.code, record.reasonCode)
        assertEquals(0, record.durationSec)
        assertEquals(1, f.callEvents("u1", CallEventKind.ENDED).size)
        assertEquals(1, f.callEvents("u2", CallEventKind.ENDED).size)
    }

    @Test
    fun `忙线 - 任一方已有呼叫即忙且不打扰`() = runTest {
        val f = Fixture()
        assertTrue(f.service.invite("u1", "call-1", "u2", video = false).deliverable)
        val outcome = f.service.invite("u2", "call-2", "u1", video = false)
        assertTrue(outcome.busy)
        assertFalse(outcome.deliverable)
        assertTrue(f.logs.isEmpty())
    }

    @Test
    fun `拒接与接听`() = runTest {
        val f = Fixture()
        f.service.invite("u1", "call-1", "u2", video = false)
        assertTrue(f.service.answer("u2", "call-1", accept = false))
        assertEquals(CallEndReason.DECLINED.code, f.logs.single().reasonCode)
        assertTrue(f.callEvents("u1", CallEventKind.ENDED).isNotEmpty())

        f.service.invite("u1", "call-2", "u2", video = false)
        assertTrue(f.service.answer("u2", "call-2", accept = true))
        val accepted = f.callEvents("u1", CallEventKind.ACCEPTED)
        assertEquals("call-2", accepted.single().callId)
    }

    @Test
    fun `信令中继只达参与对端`() = runTest {
        val f = Fixture()
        f.service.invite("u1", "call-1", "u2", video = false)
        assertTrue(f.service.signal("u1", "call-1", CallSignalBody.SessionDescription(true, "v=0")))
        val relayed = f.sent.filter { it.second == NotifyType.CALL_SIGNAL }
        assertEquals("u2", relayed.single().first)
        assertEquals("call-1", (relayed.single().third as CallSignalPayload).callId)
        // 非参与方被拒
        assertFalse(f.service.signal("u3", "call-1", CallSignalBody.SessionDescription(false, "v=0")))
        // 未知呼叫被拒
        assertFalse(f.service.signal("u1", "missing", CallSignalBody.SessionDescription(true, "v=0")))
    }

    @Test
    fun `挂断记录通话时长并解除忙线`() = runTest {
        val f = Fixture()
        f.service.invite("u1", "call-1", "u2", video = true)
        f.service.answer("u2", "call-1", accept = true)
        f.advanceMillis(75_000)
        assertTrue(f.service.hangup("u1", "call-1", CallEndReason.HANGUP.code))
        val record = f.logs.single()
        assertEquals(CallEndReason.HANGUP.code, record.reasonCode)
        assertEquals(75, record.durationSec)
        assertTrue(f.callEvents("u2", CallEventKind.ENDED).isNotEmpty())
        assertTrue(f.service.invite("u2", "call-2", "u1", video = false).deliverable)
    }

    @Test
    fun `振铃超时由清扫终结`() = runTest {
        val f = Fixture()
        f.service.invite("u1", "call-1", "u2", video = false)
        f.advanceMillis(CallService.RINGING_TIMEOUT_MILLIS + 1)
        f.service.sweepTimeouts()
        assertEquals(CallEndReason.TIMEOUT.code, f.logs.single().reasonCode)
        // 忙线解除
        assertTrue(f.service.invite("u2", "call-2", "u1", video = false).deliverable)
    }

    @Test
    fun `断连观察按连接中断终结`() = runTest {
        val f = Fixture()
        f.service.invite("u1", "call-1", "u2", video = false)
        f.service.answer("u2", "call-1", accept = true)
        f.service.onTransition(transitionOf("u2", online = false))
        // 观察者异步执行：轮询等待终结落库
        val deadline = System.currentTimeMillis() + 5_000
        while (f.logs.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val record = f.logs.single()
        assertEquals("call-1", record.callId)
        assertEquals(CallEndReason.CONNECTION_LOST.code, record.reasonCode)
    }

    @Test
    fun `未知挂断原因按正常挂断收敛`() = runTest {
        val f = Fixture()
        f.service.invite("u1", "call-1", "u2", video = false)
        f.service.answer("u2", "call-1", accept = true)
        assertTrue(f.service.hangup("u2", "call-1", reasonCode = 999))
        assertEquals(CallEndReason.HANGUP.code, f.logs.single().reasonCode)
    }
}
