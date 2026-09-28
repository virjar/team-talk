package com.virjar.tk.desktop.call

import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.model.IceServer
import com.virjar.tk.app.ui.call.DesktopVideoHandle
import com.virjar.tk.shared.call.CallMediaEngine
import com.virjar.tk.shared.call.CallMediaObserver
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面通话媒体引擎：webrtc-java（libwebrtc JVM 绑定，含本机 natives）。
 *
 * 协商与 Android 引擎一致：非 trickle，SDP 在 gathering 完成后上送；对端候选在远端
 * 描述就绪前缓冲。视频帧经 I420→BGRA 转换为 Skia 位图，按 ~15fps 限频推送；
 * 远端音频由 libwebrtc 的音频设备模块直接渲染，静音作用于本地音频轨。
 */
class DesktopCallEngine : CallMediaEngine {

    private val workExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "desktop-call-engine").apply { isDaemon = true }
    }
    private val factory = PeerConnectionFactory()
    private var pc: RTCPeerConnection? = null
    private var videoSource: VideoDeviceSource? = null
    private var audioSource: AudioTrackSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteSink: VideoTrackSink? = null
    private var observer: CallMediaObserver? = null
    private var muted = false
    private var lastFramePublishNanos = AtomicLong(0)
    private var closed = AtomicBoolean(false)

    private var pendingLocalSdp: RTCSessionDescription? = null
    private var gatheringDone = false
    private var remoteDescriptionSet = false
    private val bufferedRemoteCandidates = mutableListOf<RTCIceCandidate>()

    override val remoteVideo = MutableStateFlow<Any?>(null)
    override val localVideo = MutableStateFlow<Any?>(null)

    override fun start(video: Boolean, iceServers: List<IceServer>, observer: CallMediaObserver) {
        this.observer = observer
        gatheringDone = false
        remoteDescriptionSet = false
        val config = RTCConfiguration().apply {
            this.iceServers = iceServers.map { server ->
                RTCIceServer().apply {
                    urls = server.urls
                    username = server.username
                    password = server.credential
                }
            }
        }
        val connection = factory.createPeerConnection(config, PeerObserver())
            ?: error("PeerConnection 创建失败")
        pc = connection
        audioSource = factory.createAudioSource(AudioOptions())
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(!muted)
        }
        connection.addTrack(localAudioTrack, listOf(STREAM_ID))
        if (video) startCamera(connection)
    }

    override fun initiateOffer() {
        pc?.createOffer(RTCOfferOptions(), CreateObserver("offer"))
    }

    override fun onRemoteSessionDescription(isOffer: Boolean, sdp: String) {
        val type = if (isOffer) dev.onvoid.webrtc.RTCSdpType.OFFER else dev.onvoid.webrtc.RTCSdpType.ANSWER
        pc?.setRemoteDescription(RTCSessionDescription(type, sdp), SetObserver("set-remote"))
    }

    override fun onRemoteCandidate(candidate: CallSignalBody.IceCandidate) {
        val ice = RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        if (remoteDescriptionSet) {
            pc?.addIceCandidate(ice)
        } else {
            synchronized(bufferedRemoteCandidates) { bufferedRemoteCandidates += ice }
        }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        localAudioTrack?.setEnabled(!muted)
    }

    /** 桌面无听筒/扬声器之分；保留接口语义（扬声器常开）。 */
    override fun setSpeakerphone(enabled: Boolean) = Unit

    override fun switchCamera() = Unit

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // libwebrtc 原生引用计数要求先从 PeerConnection 摘除 sender，再逐层 dispose；
        // 逐项容错：销毁路径任何一步失败不中断其余回收（native 泄漏优于进程崩溃）。
        fun safely(action: () -> Unit) {
            runCatching(action)
        }
        safely { remoteSink?.let { sink -> remoteVideoTrack?.removeSink(sink) } }
        pc?.let { connection ->
            safely { connection.senders.forEach(connection::removeTrack) }
            safely { connection.close() }
        }
        safely { videoSource?.stop() }
        safely { videoSource?.dispose() }
        safely { localVideoTrack?.dispose() }
        safely { localAudioTrack?.dispose() }
        safely { factory.dispose() }
        remoteVideo.value = null
        localVideo.value = null
        workExecutor.shutdown()
    }

    private fun startCamera(connection: RTCPeerConnection) {
        val source = VideoDeviceSource()
        val device = MediaDevices.getVideoCaptureDevices().firstOrNull()
        device?.let { source.setVideoCaptureDevice(it) }
        source.setVideoCaptureCapability(VideoCaptureCapability(1280, 720, 30))
        source.start()
        videoSource = source
        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        localVideoTrack = track
        connection.addTrack(track, listOf(STREAM_ID))
        track.addSink(FrameSink(isLocal = true))
    }

    /** 帧泵：I420 → BGRA 位图，~15fps 限频后在引擎线程推送。 */
    private inner class FrameSink(private val isLocal: Boolean) : VideoTrackSink {
        override fun onVideoFrame(frame: VideoFrame) {
            val now = System.nanoTime()
            if (now - lastFramePublishNanos.get() < FRAME_INTERVAL_NANOS) return
            lastFramePublishNanos.set(now)
            try {
                val buffer = frame.buffer
                val i420 = buffer.toI420()
                val bitmap = i420ToBitmap(i420, buffer.getWidth(), buffer.getHeight())
                if (isLocal) localVideo.value = DesktopVideoHandle(bitmap) else remoteVideo.value = DesktopVideoHandle(bitmap)
            } catch (_: Exception) {
                // 单帧转换失败只丢帧，不断媒体
            }
        }
    }

    private inner class CreateObserver(private val tag: String) : CreateSessionDescriptionObserver {
        override fun onSuccess(description: RTCSessionDescription) {
            pc?.setLocalDescription(description, SetObserver("set-local-$tag"))
        }

        override fun onFailure(error: String?) {
            System.err.println("通话 SDP $tag 失败: $error")
        }
    }

    private inner class SetObserver(private val tag: String) : SetSessionDescriptionObserver {
        override fun onSuccess() {
            val connection = pc ?: return
            when {
                tag.startsWith("set-local-") -> connection.localDescription?.let { local ->
                    pendingLocalSdp = local
                    if (gatheringDone) {
                        pendingLocalSdp = null
                        observer?.onLocalDescription(local.sdpType == dev.onvoid.webrtc.RTCSdpType.OFFER, local.sdp)
                    }
                }
                tag == "set-remote" -> {
                    remoteDescriptionSet = true
                    val buffered = synchronized(bufferedRemoteCandidates) {
                        bufferedRemoteCandidates.also { it.clear() }
                    }
                    buffered.forEach { connection.addIceCandidate(it) }
                    if (connection.remoteDescription?.sdpType == dev.onvoid.webrtc.RTCSdpType.OFFER) {
                        connection.createAnswer(RTCAnswerOptions(), CreateObserver("answer").also {
                            // createAnswer 成功后由 CreateObserver 设置本地描述
                        })
                    }
                }
            }
        }

        override fun onFailure(error: String?) {
            System.err.println("通话 SDP $tag 失败: $error")
        }
    }

    private inner class PeerObserver : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) {
            observer?.onLocalCandidate(
                CallSignalBody.IceCandidate(candidate.sdp, candidate.sdpMid ?: "", candidate.sdpMLineIndex),
            )
        }

        override fun onIceGatheringChange(state: dev.onvoid.webrtc.RTCIceGatheringState) {
            if (state == dev.onvoid.webrtc.RTCIceGatheringState.COMPLETE) {
                gatheringDone = true
                pendingLocalSdp?.let { sdp ->
                    pendingLocalSdp = null
                    observer?.onLocalDescription(sdp.sdpType == dev.onvoid.webrtc.RTCSdpType.OFFER, sdp.sdp)
                }
            }
        }

        override fun onConnectionChange(state: RTCPeerConnectionState) {
            when (state) {
                RTCPeerConnectionState.CONNECTED -> observer?.onMediaConnected()
                RTCPeerConnectionState.FAILED, RTCPeerConnectionState.CLOSED -> observer?.onMediaFailed()
                else -> Unit
            }
        }

        override fun onIceConnectionChange(state: RTCIceConnectionState) {
            when (state) {
                RTCIceConnectionState.CONNECTED, RTCIceConnectionState.COMPLETED -> observer?.onMediaConnected()
                RTCIceConnectionState.FAILED, RTCIceConnectionState.CLOSED -> observer?.onMediaFailed()
                else -> Unit
            }
        }

        override fun onTrack(transceiver: RTCRtpTransceiver) {
            val track = transceiver.receiver.getTrack()
            if (track is VideoTrack) {
                remoteVideoTrack = track
                remoteSink = FrameSink(isLocal = false).also { track.addSink(it) }
            }
        }

        override fun onAddTrack(receiver: RTCRtpReceiver, streams: Array<out MediaStream>) {
            (receiver.getTrack() as? VideoTrack)?.let { track ->
                remoteVideoTrack = track
                remoteSink = FrameSink(isLocal = false).also { track.addSink(it) }
            }
        }
    }

    companion object {
        private const val AUDIO_TRACK_ID = "call-audio"
        private const val VIDEO_TRACK_ID = "call-video"
        private const val STREAM_ID = "call-stream"
        private val FRAME_INTERVAL_NANOS = 66_000_000L

        /** I420 → BGRA（BT.601 有限范围），产出可直接 installPixels 的字节序。 */
        fun i420ToBitmap(buffer: I420Buffer, width: Int, height: Int): org.jetbrains.skia.Bitmap {
            val y = buffer.getDataY()
            val u = buffer.getDataU()
            val v = buffer.getDataV()
            val strideY = buffer.getStrideY()
            val strideU = buffer.getStrideU()
            val strideV = buffer.getStrideV()
            val pixels = ByteArray(width * height * 4)
            var p = 0
            for (row in 0 until height) {
                val yRow = row * strideY
                val uvRow = (row / 2) * strideU
                val vRow = (row / 2) * strideV
                for (col in 0 until width) {
                    val yp = y.get(yRow + col).toInt() and 0xFF
                    val up = (u.get(uvRow + col / 2).toInt() and 0xFF) - 128
                    val vp = (v.get(vRow + col / 2).toInt() and 0xFF) - 128
                    var r = yp + (1.402f * vp).toInt()
                    var g = yp - ((0.344136f * up).toInt() + (0.714136f * vp).toInt())
                    var b = yp + (1.772f * up).toInt()
                    r = r.coerceIn(0, 255)
                    g = g.coerceIn(0, 255)
                    b = b.coerceIn(0, 255)
                    pixels[p] = b.toByte()
                    pixels[p + 1] = g.toByte()
                    pixels[p + 2] = r.toByte()
                    pixels[p + 3] = 0xFF.toByte()
                    p += 4
                }
            }
            val bitmap = org.jetbrains.skia.Bitmap()
            val info = ImageInfo.makeN32(width, height, org.jetbrains.skia.ColorAlphaType.OPAQUE)
            bitmap.installPixels(info, pixels, width * 4)
            return bitmap
        }
    }
}
