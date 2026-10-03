package com.virjar.tk.shared.call

import com.virjar.tk.protocol.CallEventPayload
import com.virjar.tk.protocol.CallSignalPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.model.CallEndReason
import com.virjar.tk.protocol.model.CallInviteOutcome
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.model.IceServer
import com.virjar.tk.protocol.rpc.gen.CallRpcProxy
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.shared.log.TkLogger
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    /** ENDED 态自动清理时延：给界面留出展示结束原因的时间，之后不再阻塞后续呼叫。 */
    private val endedAutoDismissMillis: Long = 4_000,
    /** CONNECTING（应答后媒体协商）本地看门狗时延；服务端清扫只覆盖振铃阶段。 */
    private val connectingTimeoutMillis: Long = 20_000,
) : AutoCloseable {

    private val callRpc = CallRpcProxy(rpcClient)
    private val _state = MutableStateFlow<CallViewState?>(null)
    val state: StateFlow<CallViewState?> = _state.asStateFlow()

    /** 当前引擎的远端/本地视频渲染句柄（平台对象）；引擎未启动时为 null。 */
    private val _remoteVideo = MutableStateFlow<Any?>(null)
    val remoteVideo: StateFlow<Any?> = _remoteVideo.asStateFlow()
    private val _localVideo = MutableStateFlow<Any?>(null)
    val localVideo: StateFlow<Any?> = _localVideo.asStateFlow()

    /** 本地摄像头采集失败原因（无设备/权限拒绝/首帧超时）；null 表示正常。不阻断通话。 */
    private val _localCameraNotice = MutableStateFlow<String?>(null)
    val localCameraNotice: StateFlow<String?> = _localCameraNotice.asStateFlow()

    /** 当前引擎是否有多摄像头可切换（引擎未启动为 false），UI 据此显隐切换入口。 */
    private val _cameraSwitchable = MutableStateFlow(false)
    val cameraSwitchable: StateFlow<Boolean> = _cameraSwitchable.asStateFlow()

    @Volatile private var engineFactory: CallMediaEngineFactory? = null
    @Volatile private var engineBinding: EngineBinding? = null
    @Volatile private var incomingIceServers: List<IceServer> = emptyList()
    private var listenJob: Job? = null
    private var endedAutoDismissJob: Job? = null
    private var phaseWatchdogJob: Job? = null

    private companion object {
        /** 振铃本地看门狗：略长于服务端 45s 清扫，兜底瞬时 ENDED 丢失的连接死亡场景。 */
        const val RINGING_LOCAL_TIMEOUT_MILLIS = 50_000L
    }

    /** 引擎及其状态订阅共享同一通话身份和可取消的生命周期。 */
    private class EngineBinding(
        val callId: String,
        val engine: CallMediaEngine,
        val collectors: Job,
    )

    fun bindEngineFactory(factory: CallMediaEngineFactory) {
        engineFactory = factory
        startListening()
    }

    /** 发起通话：返回服务端裁决；不可达/忙线时不进入通话界面，由调用方提示。 */
    suspend fun startOutgoing(peerUid: String, video: Boolean): CallInviteOutcome {
        ensureActive()
        // 残留的 ENDED 态（界面尚未确认或自动清理未到）不算占用，直接让位新呼叫
        if (_state.value?.phase == CallPhase.ENDED) _state.value = null
        check(_state.value == null) { "已有进行中的通话" }
        val callId = newCallId()
        val outcome = callRpc.invite(callId = callId, calleeUid = peerUid, video = video)
        if (!outcome.deliverable) return outcome
        _state.value = CallViewState(
            callId = callId, peerUid = peerUid, direction = CallDirection.OUTGOING,
            video = video, phase = CallPhase.RINGING,
        )
        armPhaseWatchdog()
        startEngine(callId, video, outcome.iceServers)
        return outcome
    }

    /** 接听来电：连接引擎并应答，进入媒体协商。 */
    suspend fun acceptIncoming() {
        ensureActive()
        val current = _state.value ?: return
        callRpc.answer(callId = current.callId, accept = true)
        startEngine(current.callId, current.video, incomingIceServers)
        incomingIceServers = emptyList()
        updateCurrent(current) { it.copy(phase = CallPhase.CONNECTING) }
        armPhaseWatchdog()
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
        stopEngine(current.callId)
        _state.value = current.copy(phase = CallPhase.ENDED, endReason = CallEndReason.HANGUP)
        scheduleEndedAutoDismiss()
    }

    /** 界面确认结束态后清除。 */
    fun dismissEnded() {
        phaseWatchdogJob?.cancel()
        endedAutoDismissJob?.cancel()
        endedAutoDismissJob = null
        if (_state.value?.phase == CallPhase.ENDED) _state.value = null
    }

    /**
     * 振铃/连接中的本地看门狗。服务端只清扫振铃超时，且瞬时 ENDED 在连接死亡时会丢；
     * 应答后的媒体协商（answer/ICE 收集）卡死没有任何清扫方，双方会永久卡在"连接中"，
     * 并以"已有进行中的通话"堵死后续呼叫——这里按阶段本地收敛。
     */
    private fun armPhaseWatchdog() {
        phaseWatchdogJob?.cancel()
        val armed = _state.value ?: return
        phaseWatchdogJob = workerScope.launch {
            val timeoutMillis = when (armed.phase) {
                CallPhase.RINGING -> RINGING_LOCAL_TIMEOUT_MILLIS
                CallPhase.CONNECTING -> connectingTimeoutMillis
                else -> return@launch
            }
            kotlinx.coroutines.delay(timeoutMillis)
            val current = _state.value ?: return@launch
            if (current.callId != armed.callId) return@launch
            when (current.phase) {
                CallPhase.RINGING -> {
                    if (current.direction == CallDirection.INCOMING) {
                        runCatching { callRpc.answer(current.callId, accept = false) }
                    } else {
                        runCatching { callRpc.hangup(current.callId, CallEndReason.TIMEOUT.code) }
                    }
                    stopEngine(current.callId)
                    _state.value = current.copy(phase = CallPhase.ENDED, endReason = CallEndReason.TIMEOUT)
                    scheduleEndedAutoDismiss()
                }
                CallPhase.CONNECTING -> {
                    runCatching { callRpc.hangup(current.callId, CallEndReason.CONNECTION_LOST.code) }
                    stopEngine(current.callId)
                    _state.value = current.copy(phase = CallPhase.ENDED, endReason = CallEndReason.CONNECTION_LOST)
                    scheduleEndedAutoDismiss()
                }
                else -> Unit
            }
        }
    }

    /** ENDED 态展示结束原因一小段时间后自动清理，避免滞留状态把后续来电/去电堵死。 */
    private fun scheduleEndedAutoDismiss() {
        phaseWatchdogJob?.cancel()
        endedAutoDismissJob?.cancel()
        endedAutoDismissJob = workerScope.launch {
            kotlinx.coroutines.delay(endedAutoDismissMillis)
            if (_state.value?.phase == CallPhase.ENDED) _state.value = null
        }
    }

    fun setMuted(muted: Boolean) {
        engineBinding?.engine?.setMuted(muted)
        _state.value?.let { current -> updateCurrent(current) { it.copy(muted = muted) } }
    }

    fun setSpeakerphone(enabled: Boolean) {
        engineBinding?.engine?.setSpeakerphone(enabled)
        _state.value?.let { current -> updateCurrent(current) { it.copy(speakerOn = enabled) } }
    }

    fun switchCamera() {
        engineBinding?.engine?.switchCamera()
    }

    override fun close() {
        phaseWatchdogJob?.cancel()
        endedAutoDismissJob?.cancel()
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
                if (current != null && current.phase != CallPhase.ENDED) {
                    // 本地忙：立即拒绝，避免铃流覆盖进行中的通话。残留 ENDED 态不算忙。
                    runCatching { callRpc.answer(event.callId, accept = false) }
                    return
                }
                if (current != null) _state.value = null
                incomingIceServers = event.iceServers
                _state.value = CallViewState(
                    callId = event.callId, peerUid = event.fromUid, direction = CallDirection.INCOMING,
                    video = event.video, phase = CallPhase.RINGING,
                )
                armPhaseWatchdog()
            }
            com.virjar.tk.protocol.model.CallEventKind.ACCEPTED -> {
                val current = _state.value ?: return
                if (current.callId != event.callId || current.direction != CallDirection.OUTGOING) return
                updateCurrent(current) { it.copy(phase = CallPhase.CONNECTING) }
                armPhaseWatchdog()
                engineBinding?.takeIf { it.callId == event.callId }?.engine?.initiateOffer()
            }
            com.virjar.tk.protocol.model.CallEventKind.ENDED -> {
                val current = _state.value ?: return
                if (current.callId != event.callId) return
                stopEngine(event.callId)
                _state.value = current.copy(
                    phase = CallPhase.ENDED,
                    endReason = CallEndReason.entries.firstOrNull { it.code == event.endReasonCode } ?: CallEndReason.HANGUP,
                )
                scheduleEndedAutoDismiss()
            }
        }
    }

    private suspend fun onCallSignal(callId: String, body: CallSignalBody) {
        val current = _state.value ?: return
        if (current.callId != callId) return
        when (body) {
            is CallSignalBody.SessionDescription -> {
                logger.trace("通话 SDP 到达: callId=$callId offer=${body.isOffer}")
                engineBinding?.takeIf { it.callId == callId }?.engine?.onRemoteSessionDescription(body.isOffer, body.sdp)
            }
            is CallSignalBody.IceCandidate -> engineBinding?.takeIf { it.callId == callId }?.engine?.onRemoteCandidate(body)
        }
    }

    private fun startEngine(callId: String, video: Boolean, iceServers: List<IceServer>) {
        val previous = engineBinding
        if (previous?.callId == callId) return
        if (previous != null) stopEngine(previous.callId, previous)
        val factory = engineFactory ?: run {
            logger.trace("通话引擎未注入，通话仅保留信令状态")
            return
        }
        val created = factory.create()
        val collectors = SupervisorJob(workerScope.coroutineContext[Job])
        val binding = EngineBinding(callId, created, collectors)
        engineBinding = binding
        _cameraSwitchable.value = created.canSwitchCamera
        val collectorScope = CoroutineScope(workerScope.coroutineContext + collectors)
        collectorScope.launch {
            created.remoteVideo.collect {
                if (isCurrentEngine(binding)) _remoteVideo.value = it
            }
        }
        collectorScope.launch {
            created.localVideo.collect {
                if (isCurrentEngine(binding)) _localVideo.value = it
            }
        }
        val observer = object : CallMediaObserver {
            override fun onLocalDescription(isOffer: Boolean, sdp: String) {
                if (!isCurrentEngine(binding)) return
                workerScope.launch {
                    if (!isCurrentEngine(binding)) return@launch
                    runCatching {
                        callRpc.signal(
                            callId = callId,
                            body = CallSignalBody.SessionDescription(isOffer, sdp),
                        )
                    }.onFailure { logger.fault("SDP 上送失败: callId=$callId") }
                }
            }

            override fun onLocalCandidate(candidate: CallSignalBody.IceCandidate) {
                if (!isCurrentEngine(binding)) return
                workerScope.launch {
                    if (!isCurrentEngine(binding)) return@launch
                    runCatching { callRpc.signal(callId, candidate) }
                }
            }

            override fun onMediaConnected() {
                workerScope.launch {
                    if (!isCurrentEngine(binding)) return@launch
                    val current = _state.value ?: return@launch
                    phaseWatchdogJob?.cancel()
                    updateCurrent(current) { it.copy(phase = CallPhase.ACTIVE) }
                }
            }

            override fun onMediaFailed() {
                if (!isCurrentEngine(binding)) return
                workerScope.launch {
                    if (!isCurrentEngine(binding)) return@launch
                    val current = _state.value ?: return@launch
                    runCatching { callRpc.hangup(callId, CallEndReason.CONNECTION_LOST.code) }
                    if (!isCurrentEngine(binding)) return@launch
                    stopEngine(callId, binding)
                    _state.value = current.copy(phase = CallPhase.ENDED, endReason = CallEndReason.CONNECTION_LOST)
                    scheduleEndedAutoDismiss()
                }
            }

            override fun onLocalCameraStalled(reason: String) {
                if (!isCurrentEngine(binding)) return
                _localCameraNotice.value = reason
                logger.fault("本地摄像头无输出: callId=$callId reason=$reason")
            }
        }
        try {
            _localCameraNotice.value = null
            created.start(video, iceServers, observer)
        } catch (failure: Throwable) {
            runCatching { stopEngine(callId, binding) }
            throw failure
        }
    }

    private fun isCurrentEngine(binding: EngineBinding): Boolean =
        engineBinding === binding && _state.value?.callId == binding.callId

    private fun stopEngine(callId: String? = null, expected: EngineBinding? = null) {
        val binding = engineBinding ?: return clearVideoHandles()
        if (callId != null && binding.callId != callId) return
        if (expected != null && binding !== expected) return
        engineBinding = null
        binding.collectors.cancel()
        try {
            binding.engine.close()
        } finally {
            clearVideoHandles()
        }
    }

    private fun clearVideoHandles() {
        _remoteVideo.value = null
        _localVideo.value = null
        _localCameraNotice.value = null
        _cameraSwitchable.value = false
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
