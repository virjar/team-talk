package com.virjar.tk.android

import android.content.Context
import com.virjar.tk.app.ui.call.AndroidLocalVideoHandle
import com.virjar.tk.app.ui.call.AndroidRemoteVideoHandle
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.model.IceServer
import com.virjar.tk.shared.call.CallMediaEngine
import com.virjar.tk.shared.call.CallMediaObserver
import com.virjar.tk.shared.call.countSdpCandidates
import com.virjar.tk.shared.call.sanitizeIceCandidate
import com.virjar.tk.shared.call.snapshotAndClear
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.concurrent.Volatile

/**
 * Android 通话媒体引擎：Stream 发行版 Google WebRTC（org.webrtc 官方 API）。
 *
 * 协商采用非 trickle：SDP 在 ICE gathering 完成后整体上送；对端候选在远端描述就绪前
 * 缓冲，远端 SDP 在引擎 start 完成前缓存（被叫 answer RPC 先于引擎就绪发出，主叫
 * offer 可能抢先到达）。听筒/扬声器与音频焦点由 AudioManager 管理，静音作用于本地音频轨。
 */
class AndroidCallEngine(private val context: Context) : CallMediaEngine {

    private val eglBase: EglBase = EglBase.create()

    private val logger = PlatformOnlyTkLogger("AndroidCallEngine")
    private var observer: CallMediaObserver? = null
    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var videoCapturer: org.webrtc.VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var muted = false
    private var speakerOn = true

    // 协商状态跨引擎线程与 webrtc 回调线程读写，可见性靠 @Volatile
    @Volatile private var pendingLocalSdp: SessionDescription? = null
    @Volatile private var gatheringDone = false
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var pendingRemoteSdp: Pair<Boolean, String>? = null
    private val bufferedRemoteCandidates = mutableListOf<IceCandidate>()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    override val remoteVideo = MutableStateFlow<Any?>(null)
    override val localVideo = MutableStateFlow<Any?>(null)

    override fun start(video: Boolean, iceServers: List<IceServer>, observer: CallMediaObserver) {
        this.observer = observer
        pendingLocalSdp = null
        pendingRemoteSdp = null
        gatheringDone = false
        remoteDescriptionSet = false
        requestAudioFocus()
        val factory = obtainFactory()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls).apply {
                if (server.username.isNotEmpty()) setUsername(server.username)
                if (server.credential.isNotEmpty()) setPassword(server.credential)
            }.createIceServer()
        }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(rtcConfig, PeerObserver()) ?: error("PeerConnection 创建失败")
        peerConnection = pc

        val constraints = MediaConstraints()
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource).also { track ->
            track.setEnabled(!muted)
            pc.addTrack(track, listOf(STREAM_ID))
        }
        if (video) startCamera(factory, pc)
        pendingRemoteSdp?.let { (isOffer, sdp) ->
            pendingRemoteSdp = null
            logger.trace("[ice] start 完成，应用缓存的远端 SDP")
            onRemoteSessionDescription(isOffer, sdp)
        }
    }

    override fun initiateOffer() {
        val pc = peerConnection ?: return
        pc.createOffer(object : SdpAdapter("offer") {
            override fun onCreateSuccess(description: SessionDescription) {
                pc.setLocalDescription(SdpAdapter("set-local-offer"), description)
            }
        }, MediaConstraints())
    }

    override fun onRemoteSessionDescription(isOffer: Boolean, sdp: String) {
        logger.trace("[ice] 远端 SDP offer=$isOffer len=${sdp.length} 候选数=${countSdpCandidates(sdp)}")
        val pc = peerConnection ?: run {
            // 信令可能先于引擎 start 到达（被叫 answer RPC 先于 startEngine 返回，主叫
            // offer 抢先送达）：缓存待 start 完成后应用，静默丢弃会让本次呼叫永远等不到协商。
            pendingRemoteSdp = isOffer to sdp
            logger.fault("[ice] pc 未就绪，缓存远端 SDP（start 后应用）")
            return
        }
        val type = if (isOffer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        // set-remote 解析失败不能冲垮信令收集器；按媒体失败终结，由看门狗/对端收敛。
        runCatching {
            pc.setRemoteDescription(SdpAdapter("set-remote"), SessionDescription(type, sdp))
        }.onFailure {
            logger.fault("[ice] set-remote 失败: ${it.message}")
            observer?.onMediaFailed()
        }
    }

    override fun onRemoteCandidate(candidate: CallSignalBody.IceCandidate) {
        logger.trace("[ice] 远端候选 mid=${candidate.sdpMid} idx=${candidate.sdpMLineIndex}: ${candidate.candidate.take(100)}")
        val ice = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, sanitizeIceCandidate(candidate.candidate))
        if (remoteDescriptionSet) {
            applyRemoteCandidate(ice)
        } else {
            synchronized(bufferedRemoteCandidates) { bufferedRemoteCandidates += ice }
        }
    }

    private fun applyRemoteCandidate(ice: IceCandidate) {
        peerConnection?.addIceCandidate(ice)
        val count = peerConnection?.remoteDescription?.description?.let { countSdpCandidates(it) } ?: -1
        logger.trace("[ice] 已应用远端候选 mid=${ice.sdpMid}，远端描述候选总数=$count")
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        localAudioTrack?.setEnabled(!muted)
    }

    override fun setSpeakerphone(enabled: Boolean) {
        speakerOn = enabled
        audioManager.isSpeakerphoneOn = enabled
    }

    override fun switchCamera() {
        val capturer = videoCapturer as? CameraVideoCapturer ?: return
        runCatching { capturer.switchCamera(null) }
    }

    /** 前后摄才有切换意义；单摄设备（或外接单摄的平板）UI 隐藏切换入口。 */
    override val canSwitchCamera: Boolean by lazy {
        runCatching { Camera2Enumerator(context).deviceNames.size > 1 }.getOrDefault(false)
    }

    override fun close() {
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        surfaceHelper?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        factory?.dispose()
        abandonAudioFocus()
        remoteVideo.value = null
        localVideo.value = null
    }

    private fun startCamera(factory: PeerConnectionFactory, pc: PeerConnection) {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return
        val capturer = enumerator.createCapturer(deviceName, null) ?: return
        val helper = SurfaceTextureHelper.create("call-capture", eglBase.eglBaseContext)
        val source = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        videoCapturer = capturer
        surfaceHelper = helper
        videoSource = source
        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        localVideoTrack = track
        pc.addTrack(track, listOf(STREAM_ID))
        localVideo.value = AndroidLocalVideoHandle(track, eglBase.eglBaseContext)
    }

    /** 远端视频轨到达（onTrack/onAddTrack 两条回调路径），包装为平台渲染句柄发布。 */
    private fun attachRemoteVideoTrack(track: VideoTrack) {
        remoteVideo.value = AndroidRemoteVideoHandle(track, eglBase.eglBaseContext)
    }

    private fun obtainFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val audioModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val created = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
        audioModule.release()
        factory = created
        return created
    }

    private fun requestAudioFocus() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = speakerOn
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun publishLocalDescriptionIfNeeded(description: SessionDescription) {
        pendingLocalSdp = description
        if (!gatheringDone) return
        pendingLocalSdp = null
        // 候选只经候选信号通道补传，不 munging 进 SDP（跨引擎解析不可靠）。
        logger.trace("[ice] 上送本地 SDP offer=${description.type == SessionDescription.Type.OFFER}")
        observer?.onLocalDescription(
            isOffer = description.type == SessionDescription.Type.OFFER,
            sdp = description.description,
        )
    }

    private open inner class SdpAdapter(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {
            publishLocalDescriptionIfNeeded(description)
        }

        override fun onSetSuccess() {
            val pc = peerConnection ?: return
            when (tag) {
                "set-local-offer", "set-local-answer" -> publishLocalDescriptionIfNeeded(pc.localDescription)
                "set-remote" -> {
                    remoteDescriptionSet = true
                    // 先取快照再清空：also{clear()} 会返回已清空的接收者，缓冲候选被整体丢弃
                    val buffered = synchronized(bufferedRemoteCandidates) { snapshotAndClear(bufferedRemoteCandidates) }
                    runCatching { buffered.forEach { pc.addIceCandidate(it) } }
                        .onFailure { logger.fault("[ice] 候选批量应用失败: ${it.message}") }
                    if (pc.remoteDescription?.type == SessionDescription.Type.OFFER) {
                        // createAnswer 成功后经 SdpAdapter 走 set-local-answer 链上送
                        pc.createAnswer(object : SdpAdapter("answer") {
                            override fun onCreateSuccess(description: SessionDescription) {
                                pc.setLocalDescription(SdpAdapter("set-local-answer"), description)
                            }
                        }, MediaConstraints())
                    }
                }
            }
        }

        override fun onCreateFailure(error: String?) {
            logger.fault("SDP $tag 失败: $error")
        }

        override fun onSetFailure(error: String?) {
            logger.fault("SDP $tag 失败: $error")
        }
    }

    private inner class PeerObserver : PeerConnection.Observer {
    override fun onIceCandidate(candidate: IceCandidate) {
        // 上送清洗后的行（shared 契约）：原始行带 ufrag/network-id 扩展，跨引擎对端解析不可靠
        val sanitized = sanitizeIceCandidate(candidate.sdp)
        logger.trace("[ice] 本地候选: ${sanitized.take(110)}")
        observer?.onLocalCandidate(
            CallSignalBody.IceCandidate(
                candidate = sanitized,
                sdpMid = candidate.sdpMid ?: "",
                sdpMLineIndex = candidate.sdpMLineIndex,
            ),
        )
    }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            logger.trace("[ice] gather 状态: $state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                gatheringDone = true
                pendingLocalSdp?.let { sdp ->
                    pendingLocalSdp = null
                    // 候选只经信号通道补传，不 munging 进 SDP（同上）。
                    observer?.onLocalDescription(sdp.type == SessionDescription.Type.OFFER, sdp.description)
                }
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            logger.trace("[ice] ICE 连接状态: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> observer?.onMediaConnected()
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.CLOSED,
                -> observer?.onMediaFailed()
                else -> Unit
            }
        }

        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            (receiver.track() as? VideoTrack)?.let(::attachRemoteVideoTrack)
        }

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let(::attachRemoteVideoTrack)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.FAILED) observer?.onMediaFailed()
        }

        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    }

    companion object {
        private const val AUDIO_TRACK_ID = "call-audio"
        private const val VIDEO_TRACK_ID = "call-video"
        private const val STREAM_ID = "call-stream"
    }
}
