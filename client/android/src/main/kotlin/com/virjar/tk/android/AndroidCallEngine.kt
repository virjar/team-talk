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
import org.webrtc.MediaStreamTrack
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Android 通话媒体引擎：Stream 发行版 Google WebRTC（org.webrtc 官方 API）。
 *
 * 协商采用非 trickle：SDP 在 ICE gathering 完成后整体上送；对端候选在远端描述就绪前
 * 缓冲。听筒/扬声器与音频焦点由 AudioManager 管理，静音作用于本地音频轨。
 */
class AndroidCallEngine(private val context: Context) : CallMediaEngine {

    private val eglBase: EglBase = EglBase.create()
    private val workExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "call-engine").apply { isDaemon = true }
    }

    private var observer: CallMediaObserver? = null
    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var videoCapturer: org.webrtc.VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoEnabled = false
    private var muted = false
    private var speakerOn = true

    private var pendingLocalSdp: SessionDescription? = null
    private val gatheredCandidates = mutableListOf<String>()
    private var gatheringDone = false
    private var remoteDescriptionSet = false
    private val bufferedRemoteCandidates = mutableListOf<IceCandidate>()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    override val remoteVideo = MutableStateFlow<Any?>(null)
    override val localVideo = MutableStateFlow<Any?>(null)

    override fun start(video: Boolean, iceServers: List<IceServer>, observer: CallMediaObserver) {
        this.observer = observer
        this.videoEnabled = video
        synchronized(gatheredCandidates) { gatheredCandidates.clear() }
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
        android.util.Log.e(TAG, "[ice] 远端 SDP offer=$isOffer len=${sdp.length} 完整内容↓")
        android.util.Log.e(TAG, "[ice] SDP-BEGIN:" + sdp.replace("\r\n", "|") + ":SDP-END")
        val pc = peerConnection ?: return
        val type = if (isOffer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        // set-remote 解析失败不能冲垮信令收集器；按媒体失败终结，由看门狗/对端收敛。
        runCatching {
            pc.setRemoteDescription(SdpAdapter("set-remote"), SessionDescription(type, sdp))
        }.onFailure {
            android.util.Log.e(TAG, "[ice] set-remote 失败: ${it.message}")
            observer?.onMediaFailed()
        }
    }

    override fun onRemoteCandidate(candidate: CallSignalBody.IceCandidate) {
        android.util.Log.e(TAG, "[ice] 远端候选 mid=${candidate.sdpMid} idx=${candidate.sdpMLineIndex}: ${candidate.candidate.take(100)}")
        val ice = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, sanitizeCandidate(candidate.candidate))
        if (remoteDescriptionSet) {
            applyRemoteCandidate(ice)
        } else {
            synchronized(bufferedRemoteCandidates) { bufferedRemoteCandidates += ice }
        }
    }

    private fun applyRemoteCandidate(ice: IceCandidate) {
        peerConnection?.addIceCandidate(ice)
        val remote = peerConnection?.remoteDescription
        val count = remote?.description?.lines()?.count { it.contains("a=candidate", ignoreCase = true) } ?: -1
        android.util.Log.e(TAG, "[ice] 已应用远端候选 mid=${ice.sdpMid}，当前远端描述候选总数=$count")
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
        workExecutor.shutdown()
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
        if (description.type == SessionDescription.Type.OFFER || description.type == SessionDescription.Type.ANSWER) {
            pendingLocalSdp = description
        }
        if (!gatheringDone) return
        pendingLocalSdp?.let { sdp ->
            pendingLocalSdp = null
            // 候选只经候选信号通道补传，不 munging 进 SDP（跨引擎解析不可靠）。
            android.util.Log.e(TAG, "[ice] 上送本地 SDP offer=${sdp.type == SessionDescription.Type.OFFER}")
            observer?.onLocalDescription(
                isOffer = sdp.type == SessionDescription.Type.OFFER,
                sdp = sdp.description,
            )
        }
    }

    /** 剥离候选行里的 ufrag/network-id/network-cost 可选扩展：旧版 libwebrtc 解析含
     *  ufrag 的候选行会失败（对端 webrtc-java 会带），与桌面引擎的剥离规则一致。 */
    private fun sanitizeCandidate(sdp: String): String =
        sdp.replace(Regex(" ufrag \\S+"), "")
            .replace(Regex(" network-id \\S+"), "")
            .replace(Regex(" network-cost \\S+"), "")
            .trim()

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
                    val buffered = synchronized(bufferedRemoteCandidates) {
                        bufferedRemoteCandidates.also { it.clear() }
                    }
                    runCatching { buffered.forEach { pc.addIceCandidate(it) } }
                        .onFailure { android.util.Log.e(TAG, "[ice] 候选批量应用失败: ${it.message}") }
                    if (tag == "set-remote" && pc.remoteDescription?.type == SessionDescription.Type.OFFER) {
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
            android.util.Log.e(TAG, "SDP $tag 失败: $error")
        }

        override fun onSetFailure(error: String?) {
            android.util.Log.e(TAG, "SDP $tag 失败: $error")
        }
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val sanitized = sanitizeCandidate(candidate.sdp)
            android.util.Log.e(TAG, "[ice] 本地候选: ${sanitized.take(110)}")
            synchronized(gatheredCandidates) { gatheredCandidates.add(sanitized) }
            observer?.onLocalCandidate(
                CallSignalBody.IceCandidate(
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid ?: "",
                    sdpMLineIndex = candidate.sdpMLineIndex,
                ),
            )
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            android.util.Log.e(TAG, "[ice] gather 状态: $state")
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
            android.util.Log.e(TAG, "[ice] ICE 连接状态: $state")
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
            val track = receiver.track() ?: return
            if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                remoteVideo.value = AndroidRemoteVideoHandle(track as VideoTrack, eglBase.eglBaseContext)
            }
        }

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            val track = transceiver.receiver.track() ?: return
            if (track is VideoTrack) {
                remoteVideo.value = AndroidRemoteVideoHandle(track, eglBase.eglBaseContext)
            }
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
        private const val TAG = "AndroidCallEngine"
        private const val AUDIO_TRACK_ID = "call-audio"
        private const val VIDEO_TRACK_ID = "call-video"
        private const val STREAM_ID = "call-stream"
    }
}
