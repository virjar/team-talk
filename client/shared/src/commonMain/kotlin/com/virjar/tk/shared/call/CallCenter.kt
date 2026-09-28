package com.virjar.tk.shared.call

import com.virjar.tk.protocol.CallEventPayload
import com.virjar.tk.protocol.CallSignalPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.model.CallEndReason
import com.virjar.tk.protocol.model.CallInviteOutcome
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.rpc.gen.CallRpcProxy
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.shared.log.TkLogger
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话级 1:1 通话编排（协议 minor 0.5）：消费 [EventProcessor] 的瞬时通话信令，
 * 驱动 [CallMediaEngine] 完成 WebRTC 协商，向 UI 暴露单一 [CallViewState]。
 *
 * 线程模型：引擎回调发生在引擎线程，本类统一在会话 worker scope 内收敛状态；
 * 信令经同一 TCP 连接按序到达，媒体协商采用 gathering 完成后整段 SDP 交换（非 trickle），
 * ICE 候选中继通道保留但不依赖。
 *
 * 生命周期：由平台壳在会话激活后 [bindEngineFactory] 注入引擎工厂；[close] 在会话退役时
 * 挂断媒体并清空状态（服务端超时/断连收敛兜底）。
 */
class CallCenter(
    rpcClient: RpcInvoker,
    private val callEvents: SharedFlow<CallEventPayload>,
    private val callSignals: SharedFlow<CallSignalPayload>,
    private val workerScope: CoroutineScope,
    private val ensureActive: () -> Unit,
    private val logger: TkLogger,
) : AutoCloseable {

    private val callRpc = CallRpcProxy(rpcClient)
    private val _state = MutableStateFlow<CallViewState?>(null)
    val state: StateFlow<CallViewState?> = _state.asStateFlow()

    /** 当前引擎的远端/本地视频渲染句柄（平台对象）；引擎未启动时为 null。 */
    private val _remoteVideo = MutableStateFlow<Any?>(null)
    val remoteVideo: StateFlow<Any?> = _remoteVideo.asStateFlow()
    private val _localVideo = MutableStateFlow<Any?>(null)
    val localVideo: StateFlow<Any?> = _localVideo.asStateFlow()

    @Volatile private var engineFactory: CallMediaEngineFactory? = null
    @Volatile private var engine: CallMediaEngine? = null
    @Volatile private var incomingIceServers: List<com.virjar.tk.protocol.model.IceServer> = emptyList()
    private var listenJob: Job? = null

    fun bindEngineFactory(factory: CallMediaEngineFactory) {
        engineFactory = factory
        startListening()
    }

    /** 发起通话：返回服务端裁决；不可达/忙线时不进入通话界面，由调用方提示。 */
    suspend fun startOutgoing(peerUid: String, video: Boolean): CallInviteOutcome {
        ensureActive()
        check(_state.value == null) { "已有进行中的通话" }
        val callId = newCallId()
        val outcome = callRpc.invite(callId = callId, calleeUid = peerUid, video = video)
        if (!outcome.deliverable) return outcome
        _state.value = CallViewState(
            callId = callId, peerUid = peerUid, direction = CallDirection.OUTGOING,
            video = video, phase = CallPhase.RINGING,
        )
        startEngine(video, outcome.iceServers)
        return outcome
    }

    /** 接听来电：连接引擎并应答，进入媒体协商。 */
    suspend fun acceptIncoming() {
        ensureActive()
        val current = _state.value ?: return
        callRpc.answer(callId = current.callId, accept = true)
        startEngine(current.video, incomingIceServers)
        incomingIceServers = emptyList()
        updateCurrent(current) { it.copy(phase = CallPhase.CONNECTING) }
    }

    /** 拒接来电。 */
    suspend fun declineIncoming() {
        val current = _state.value ?: return
        callRpc.answer(callId = current.callId, accept = false)
        incomingIceServers = emptyList()
    }

    /** 本地挂断：振铃去电按取消、接通后按挂断终结。 */
    suspend fun hangUp() {
        val current = _state.value ?: return
        when (current.phase) {
            CallPhase.RINGING -> if (current.direction == CallDirection.OUTGOING) {
                callRpc.hangup(current.callId, CallEndReason.CANCELLED.code)
            }
            else -> callRpc.hangup(current.callId, CallEndReason.HANGUP.code)
        }
        stopEngine()
        _state.value = current.copy(phase = CallPhase.ENDED, endReason = CallEndReason.HANGUP)
    }

    /** 界面确认结束态后清除。 */
    fun dismissEnded() {
        if (_state.value?.phase == CallPhase.ENDED) _state.value = null
    }

    fun setMuted(muted: Boolean) {
        engine?.setMuted(muted)
        _state.value?.let { current -> updateCurrent(current) { it.copy(muted = muted) } }
    }

    fun setSpeakerphone(enabled: Boolean) {
        engine?.setSpeakerphone(enabled)
        _state.value?.let { current -> updateCurrent(current) { it.copy(speakerOn = enabled) } }
    }

    fun switchCamera() {
        engine?.switchCamera()
    }

    override fun close() {
        listenJob?.cancel()
        stopEngine()
        _state.value = null
    }

    private fun startListening() {
        if (listenJob != null) return
        listenJob = workerScope.launch {
            launch {
                callEvents.collect { onCallEvent(it) }
            }
            launch {
                callSignals.collect { onCallSignal(it.callId, it.body) }
            }
        }
    }

    private suspend fun onCallEvent(event: CallEventPayload) {
        when (event.kind) {
            com.virjar.tk.protocol.model.CallEventKind.RING -> {
                val current = _state.value
                if (current != null) {
                    // 本地忙：立即拒绝，避免铃流覆盖进行中的通话
                    runCatching { callRpc.answer(event.callId, accept = false) }
                    return
                }
                incomingIceServers = event.iceServers
                _state.value = CallViewState(
                    callId = event.callId, peerUid = event.fromUid, direction = CallDirection.INCOMING,
                    video = event.video, phase = CallPhase.RINGING,
                )
            }
            com.virjar.tk.protocol.model.CallEventKind.ACCEPTED -> {
                val current = _state.value ?: return
                if (current.callId != event.callId || current.direction != CallDirection.OUTGOING) return
                updateCurrent(current) { it.copy(phase = CallPhase.CONNECTING) }
                engine?.initiateOffer()
            }
            com.virjar.tk.protocol.model.CallEventKind.ENDED -> {
                val current = _state.value ?: return
                if (current.callId != event.callId) return
                stopEngine()
                _state.value = current.copy(
                    phase = CallPhase.ENDED,
                    endReason = CallEndReason.entries.firstOrNull { it.code == event.endReasonCode } ?: CallEndReason.HANGUP,
                )
            }
        }
    }

    private suspend fun onCallSignal(callId: String, body: CallSignalBody) {
        val current = _state.value ?: return
        if (current.callId != callId) return
        when (body) {
            is CallSignalBody.SessionDescription -> {
                logger.trace("通话 SDP 到达: callId=$callId offer=${body.isOffer}")
                engine?.onRemoteSessionDescription(body.isOffer, body.sdp)
            }
            is CallSignalBody.IceCandidate -> engine?.onRemoteCandidate(body)
        }
    }

    private fun startEngine(video: Boolean, iceServers: List<com.virjar.tk.protocol.model.IceServer>) {
        val factory = engineFactory ?: run {
            logger.trace("通话引擎未注入，通话仅保留信令状态")
            return
        }
        val created = factory.create()
        engine = created
        workerScope.launch {
            created.remoteVideo.collect { _remoteVideo.value = it }
        }
        workerScope.launch {
            created.localVideo.collect { _localVideo.value = it }
        }
        created.start(video, iceServers, observer = object : CallMediaObserver {
            override fun onLocalDescription(isOffer: Boolean, sdp: String) {
                val callId = _state.value?.callId ?: return
                workerScope.launch {
                    runCatching {
                        callRpc.signal(
                            callId = callId,
                            body = CallSignalBody.SessionDescription(isOffer, sdp),
                        )
                    }.onFailure { logger.fault("SDP 上送失败: callId=$callId") }
                }
            }

            override fun onLocalCandidate(candidate: CallSignalBody.IceCandidate) {
                val callId = _state.value?.callId ?: return
                workerScope.launch {
                    runCatching { callRpc.signal(callId, candidate) }
                }
            }

            override fun onMediaConnected() {
                val current = _state.value ?: return
                updateCurrent(current) { it.copy(phase = CallPhase.ACTIVE) }
            }

            override fun onMediaFailed() {
                val current = _state.value ?: return
                workerScope.launch {
                    runCatching { callRpc.hangup(current.callId, CallEndReason.CONNECTION_LOST.code) }
                    stopEngine()
                    _state.value = current.copy(phase = CallPhase.ENDED, endReason = CallEndReason.CONNECTION_LOST)
                }
            }
        })
    }

    private fun stopEngine() {
        engine?.use { }
        engine = null
        _remoteVideo.value = null
        _localVideo.value = null
    }

    private fun updateCurrent(current: CallViewState, transform: (CallViewState) -> CallViewState) {
        val latest = _state.value ?: return
        if (latest.callId == current.callId) _state.value = transform(latest)
    }

    private fun newCallId(): String {
        // UUID v4（commonMain 无 UUID API；按 RFC 4122 手工置位）
        val bytes = ByteArray(16).also { kotlin.random.Random.Default.nextBytes(it) }
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hexChars = "0123456789abcdef"
        val hex = bytes.joinToString("") { b ->
            val v = b.toInt() and 0xFF
            "${hexChars[v ushr 4]}${hexChars[v and 0xF]}"
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
