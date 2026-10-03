package com.virjar.tk.shared.call

import com.virjar.tk.protocol.CallEventPayload
import com.virjar.tk.protocol.CallSignalPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.model.CallEndReason
import com.virjar.tk.protocol.model.CallEventKind
import com.virjar.tk.protocol.model.CallInviteOutcome
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.model.IceServer
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CallCenter 编排行为：假 RPC + 假引擎 + 内存信令流（无网络）。 */
class CallCenterTest {

    private class FakeEngine : CallMediaEngine {
        val events = mutableListOf<String>()
        var observer: CallMediaObserver? = null
        override val remoteVideo: StateFlow<Any?> = MutableStateFlow(null)
        override val localVideo: StateFlow<Any?> = MutableStateFlow(null)

        override fun start(video: Boolean, iceServers: List<IceServer>, observer: CallMediaObserver) {
            events += "start:$video"
            this.observer = observer
        }

        fun connected() {
            observer?.onMediaConnected()
        }

        fun failed() {
            observer?.onMediaFailed()
        }

        override fun initiateOffer() {
            events += "initiateOffer"
            observer?.onLocalDescription(isOffer = true, "local-offer")
        }

        override fun onRemoteSessionDescription(isOffer: Boolean, sdp: String) {
            events += "remoteSdp:$isOffer"
        }

        override fun onRemoteCandidate(candidate: CallSignalBody.IceCandidate) {
            events += "remoteCandidate"
        }

        override fun setMuted(muted: Boolean) {
            events += "muted:$muted"
        }

        override fun setSpeakerphone(enabled: Boolean) {
            events += "speaker:$enabled"
        }

        override fun switchCamera() {
            events += "switchCamera"
        }

        override fun close() {
            events += "close"
        }
    }

    private class Harness(
        private val endedAutoDismissMillis: Long = 4_000,
        private val connectingTimeoutMillis: Long = 20_000,
    ) {
        val rpc = FakeRpc()
        val callEvents = MutableSharedFlow<CallEventPayload>(extraBufferCapacity = 16)
        val callSignals = MutableSharedFlow<CallSignalPayload>(extraBufferCapacity = 16)
        val engines = mutableListOf<FakeEngine>()
        /** 真实时间执行域：runTest 虚拟时钟不推进外部事件等待，官方建议经此视图逃逸。 */
        private val realTime = Dispatchers.Default.limitedParallelism(1)

        val center = CallCenter(
            rpcClient = rpc,
            callEvents = callEvents,
            callSignals = callSignals,
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ensureActive = {},
            logger = PlatformOnlyTkLogger("CallCenterTest"),
            endedAutoDismissMillis = endedAutoDismissMillis,
            connectingTimeoutMillis = connectingTimeoutMillis,
        )

        init {
            center.bindEngineFactory {
                FakeEngine().also { engines += it }
            }
        }

        /**
         * 确定性等待信令订阅就绪：SharedFlow 在订阅建立前 emit 会直接丢弃。
         * runTest 的虚拟时钟不推进挂起等待，经 limitedParallelisim 视图逃逸到真实时间
         * （kotlinx 官方建议），等待 subscriptionCount 快照翻转。
         */
        suspend fun awaitSubscribed() {
            try {
                kotlinx.coroutines.withContext(realTime) {
                    kotlinx.coroutines.withTimeout(5_000) {
                        callEvents.subscriptionCount.first { it > 0 }
                        callSignals.subscriptionCount.first { it > 0 }
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                assertTrue(false, "信令订阅未在 5s 内就绪")
            }
        }

        /** 条件轮询等待：并行测试负载下固定 sleep 会抖动，以状态收敛为准。 */
        suspend fun awaitTrue(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
            try {
                kotlinx.coroutines.withContext(realTime) {
                    kotlinx.coroutines.withTimeout(timeoutMillis) {
                        while (!condition()) kotlinx.coroutines.delay(20)
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                // 落到下方断言给出统一失败信息
            }
            assertTrue(condition(), "condition not met within timeout")
        }

        class FakeRpc : RpcInvoker {
            val calls = mutableListOf<Pair<String, Int>>()
            var inviteOutcome = CallInviteOutcome(deliverable = true, busy = false, iceServers = emptyList())

            override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                calls += service to methodId
                // 与生成 Proxy 的解码约定一致：invite 返回 outcome，其余返回 Boolean
                val body = when (methodId) {
                    1 -> com.virjar.tk.protocol.PacketBuffer()
                        .also { inviteOutcome.writeTo(it) }.toByteArray()
                    else -> com.virjar.tk.protocol.PacketBuffer()
                        .also { it.writeBoolean(true) }.toByteArray()
                }
                return ResponsePayload(1, 0, body)
            }
        }
    }

    private fun ring(callId: String, from: String = "peer", video: Boolean = false) = CallEventPayload(
        callId = callId, fromUid = from, kind = CallEventKind.RING,
        video = video, endReasonCode = 0, iceServers = listOf(IceServer(listOf("stun:x:1"))),
    )

    private fun ended(callId: String, reason: CallEndReason) = CallEventPayload(
        callId = callId, fromUid = "peer", kind = CallEventKind.ENDED,
        video = false, endReasonCode = reason.code, iceServers = emptyList(),
    )

    @Test
    fun `主叫流程 - 振铃接通后发起offer并挂断`() = runTest {
        val h = Harness()
        h.awaitSubscribed()
        val outcome = h.center.startOutgoing("peer", video = true)
        assertTrue(outcome.deliverable)
        val view = h.center.state.value!!
        assertEquals(CallDirection.OUTGOING, view.direction)
        assertEquals(CallPhase.RINGING, view.phase)
        assertTrue(view.video)

        h.callEvents.emit(CallEventPayload(view.callId, "peer", CallEventKind.ACCEPTED, true, 0, emptyList()))
        h.awaitTrue { h.engines.single().events.contains("initiateOffer") }

        h.engines.single().connected()
        h.awaitTrue { h.center.state.value?.phase == CallPhase.ACTIVE }

        h.center.hangUp()
        assertEquals(CallPhase.ENDED, h.center.state.value!!.phase)
        assertTrue(h.engines.single().events.contains("close"))
        h.center.dismissEnded()
        assertNull(h.center.state.value)
    }

    @Test
    fun `被叫流程 - 来电接听后收到offer并进入ACTIVE`() = runTest {
        val h = Harness()
        h.awaitSubscribed()
        h.callEvents.emit(ring("call-9", video = true))
        h.awaitTrue { h.center.state.value != null }
        val view = h.center.state.value!!
        assertEquals(CallDirection.INCOMING, view.direction)
        assertTrue(view.video)

        h.center.acceptIncoming()
        h.callSignals.emit(CallSignalPayload("call-9", CallSignalBody.SessionDescription(true, "remote-sdp")))
        h.awaitTrue { h.engines.single().events.contains("remoteSdp:true") }

        // 被叫引擎完成 answer 上送
        h.engines.single().observer!!.onLocalDescription(isOffer = false, sdp = "answer-sdp")
        h.awaitTrue { h.rpc.calls.contains("call" to 3) }

        h.engines.single().connected()
        h.awaitTrue { h.center.state.value?.phase == CallPhase.ACTIVE }
    }

    @Test
    fun `对端终结 - 进入ENDED并清理引擎`() = runTest {
        val h = Harness()
        h.awaitSubscribed()
        h.center.startOutgoing("peer", video = false)
        val callId = h.center.state.value!!.callId
        h.callEvents.emit(ended(callId, CallEndReason.CANCELLED))
        h.awaitTrue { h.center.state.value?.phase == CallPhase.ENDED }
        assertEquals(CallPhase.ENDED, h.center.state.value!!.phase)
        assertEquals(CallEndReason.CANCELLED, h.center.state.value!!.endReason)
        assertTrue(h.engines.single().events.contains("close"))
    }

    @Test
    fun `本地忙时来电自动拒接且不打断当前通话`() = runTest {
        val h = Harness()
        h.awaitSubscribed()
        h.center.startOutgoing("peer-a", video = false)
        val firstCallId = h.center.state.value!!.callId
        h.callEvents.emit(ring("incoming-1"))
        assertEquals(firstCallId, h.center.state.value!!.callId)
        h.awaitTrue { h.rpc.calls.contains("call" to 2) } // answer(false)
    }

    @Test
    fun `媒体失败按连接中断终结`() = runTest {
        val h = Harness()
        h.awaitSubscribed()
        h.center.startOutgoing("peer", video = false)
        h.engines.single().failed()
        h.awaitTrue { h.center.state.value?.phase == CallPhase.ENDED }
        assertEquals(CallEndReason.CONNECTION_LOST, h.center.state.value!!.endReason)
    }

    @Test
    fun `静音与扬声器转发到引擎`() = runTest {
        val h = Harness()
        h.awaitSubscribed()
        h.center.startOutgoing("peer", video = false)
        h.center.setMuted(true)
        h.center.setSpeakerphone(false)
        h.center.switchCamera()
        val events = h.engines.single().events
        assertTrue(events.contains("muted:true") && events.contains("speaker:false") && events.contains("switchCamera"))
        assertEquals(true, h.center.state.value!!.muted)
        assertEquals(false, h.center.state.value!!.speakerOn)
    }

    @Test
    fun `残留结束态不阻塞再次发起`() = runTest {
        val h = Harness()
        h.awaitSubscribed()
        h.center.startOutgoing("peer", video = false)
        val firstCallId = h.center.state.value!!.callId
        h.callEvents.emit(ended(firstCallId, CallEndReason.DECLINED))
        h.awaitTrue { h.center.state.value?.phase == CallPhase.ENDED }

        val outcome = h.center.startOutgoing("peer", video = true)
        assertTrue(outcome.deliverable)
        val second = h.center.state.value!!
        assertTrue(second.callId != firstCallId)
        assertEquals(CallPhase.RINGING, second.phase)
    }

    @Test
    fun `残留结束态不自动拒接新来电`() = runTest {
        val h = Harness()
        h.awaitSubscribed()
        h.center.startOutgoing("peer", video = false)
        val firstCallId = h.center.state.value!!.callId
        h.callEvents.emit(ended(firstCallId, CallEndReason.TIMEOUT))
        h.awaitTrue { h.center.state.value?.phase == CallPhase.ENDED }

        h.callEvents.emit(ring("incoming-2", video = true))
        h.awaitTrue { h.center.state.value?.callId == "incoming-2" }
        val view = h.center.state.value!!
        assertEquals(CallDirection.INCOMING, view.direction)
        assertEquals(CallPhase.RINGING, view.phase)
    }

    @Test
    fun `结束态超时自动清理`() = runTest {
        val h = Harness(endedAutoDismissMillis = 150)
        h.awaitSubscribed()
        h.center.startOutgoing("peer", video = false)
        val callId = h.center.state.value!!.callId
        h.callEvents.emit(ended(callId, CallEndReason.HANGUP))
        h.awaitTrue { h.center.state.value?.phase == CallPhase.ENDED }
        h.awaitTrue(timeoutMillis = 5_000) { h.center.state.value == null }
    }

    @Test
    fun `连接中超时本地收敛为连接中断`() = runTest {
        val h = Harness(connectingTimeoutMillis = 200)
        h.awaitSubscribed()
        h.center.startOutgoing("peer", video = false)
        val callId = h.center.state.value!!.callId
        h.callEvents.emit(CallEventPayload(callId, "peer", CallEventKind.ACCEPTED, false, 0, emptyList()))
        h.awaitTrue { h.center.state.value?.phase == CallPhase.CONNECTING }
        h.awaitTrue(timeoutMillis = 5_000) { h.center.state.value?.phase == CallPhase.ENDED }
        assertEquals(CallEndReason.CONNECTION_LOST, h.center.state.value!!.endReason)
        h.awaitTrue(timeoutMillis = 5_000) { h.center.state.value == null }
    }

    @Test
    fun `媒体接通撤销连接中看门狗`() = runTest {
        val h = Harness(connectingTimeoutMillis = 200)
        h.awaitSubscribed()
        h.center.startOutgoing("peer", video = false)
        val callId = h.center.state.value!!.callId
        h.callEvents.emit(CallEventPayload(callId, "peer", CallEventKind.ACCEPTED, false, 0, emptyList()))
        h.awaitTrue { h.center.state.value?.phase == CallPhase.CONNECTING }
        h.engines.single().connected()
        h.awaitTrue { h.center.state.value?.phase == CallPhase.ACTIVE }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.delay(400)
        }
        assertEquals(CallPhase.ACTIVE, h.center.state.value!!.phase)
    }
}
