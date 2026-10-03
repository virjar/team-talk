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

    // webrtc-java 的 factory 持有 native 全局组件（网络线程/ADM/field trials）：
    // 每次 dispose 会破坏全局状态，同进程后续通话的 ICE 全部失效（首通正常、
    // 之后永久"连接中"的系统性根因）。进程级单例，引擎只建 PC/轨，不 dispose。
    private val factory: PeerConnectionFactory get() = Companion.sharedFactory
    private var pc: RTCPeerConnection? = null
    private var pendingRemoteSdp: Pair<Boolean, String>? = null
    private var probeChannel: dev.onvoid.webrtc.RTCDataChannel? = null
    private var videoSource: VideoDeviceSource? = null
    private var audioSource: AudioTrackSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteSink: VideoTrackSink? = null
    private var observer: CallMediaObserver? = null
    private var muted = false
    private var lastFramePublishNanos = AtomicLong(0)
    private val localVideoFirstFrame = AtomicBoolean(false)
    private var closed = AtomicBoolean(false)

    private var pendingLocalSdp: RTCSessionDescription? = null
    private val gatheredCandidates = mutableListOf<String>()
    private val sentCandidates = java.util.concurrent.atomic.AtomicInteger(0)
    private var gatheringDone = false
    private var remoteDescriptionSet = false
    private val bufferedRemoteCandidates = mutableListOf<RTCIceCandidate>()
    private val negotiationLock = Any()

    /** 剥离 candidate 行里的可选扩展属性：webrtc-java 会带 ufrag/network-id/network-cost，
     *  旧版 libwebrtc（Android）解析含 ufrag 的候选行会失败，导致 set-remote 整体报
     *  "SessionDescription is NULL"、addIceCandidate 静默丢弃。这些属性均为可选提示。 */
    private fun sanitizeCandidate(sdp: String): String =
        sdp.replace(Regex(" ufrag \\S+"), "")
            .replace(Regex(" network-id \\S+"), "")
            .replace(Regex(" network-cost \\S+"), "")
            .trim()
    private val logger = com.virjar.tk.shared.log.PlatformOnlyTkLogger("DesktopCallEngine")

    /** gathering 兜底时延：非 trickle 等 COMPLETE，但 STUN/VPN 异常时 gather 可能停滞，
     *  超时后先上送 SDP，迟到的候选经既有候选通道补传（wire 保留作兜底）。 */
    private val gatherFallbackExecutor = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "desktop-call-gather-fallback").apply { isDaemon = true }
    }

    override val remoteVideo = MutableStateFlow<Any?>(null)
    override val localVideo = MutableStateFlow<Any?>(null)

    override fun start(video: Boolean, iceServers: List<IceServer>, observer: CallMediaObserver) {
        this.observer = observer
        pendingLocalSdp = null
        sentCandidates.set(0)
        synchronized(negotiationLock) { gatheredCandidates.clear() }
        gatheringDone = false
        remoteDescriptionSet = false
        logger.fault("[ice] ${System.identityHashCode(this)} start video=$video iceServers=" + iceServers.joinToString("|") { server -> server.urls.joinToString(",") })
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
        // 内部探针 DataChannel：除通话内控制信令的承载外，还规避 webrtc-java 在
        // 纯音频 BUNDLE 协商下 ICE 检查不启动的缺陷（本机引擎对接可复现：无 DC
        // 时 CHECKING 后死，有 DC 立即 CONNECTED）。
        probeChannel = connection.createDataChannel("call-probe", dev.onvoid.webrtc.RTCDataChannelInit())
        audioSource = factory.createAudioSource(AudioOptions())
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(!muted)
        }
        connection.addTrack(localAudioTrack, listOf(STREAM_ID))
        if (video) startCamera(connection)
        pendingRemoteSdp?.let { (isOffer, sdp) ->
            pendingRemoteSdp = null
            logger.fault("[ice] ${System.identityHashCode(this)} start 完成，应用缓存的远端 SDP")
            onRemoteSessionDescription(isOffer, sdp)
        }
    }

    override fun initiateOffer() {
        pc?.createOffer(RTCOfferOptions(), CreateObserver("offer"))
    }

    override fun onRemoteSessionDescription(isOffer: Boolean, sdp: String) {
        logger.fault("[ice] ${System.identityHashCode(this)} 远端 SDP offer=$isOffer len=" + sdp.length + " 候选: " +
            sdp.lines().filter { it.contains("candidate", ignoreCase = true) }
                .joinToString(" / ") { it.trim().take(90) })
        val pc = pc ?: run {
            // 信令可能先于引擎 start 到达（主叫 RING 后立即 gather）：缓存待 start
            // 完成后应用。静默丢弃会让本次呼叫永远等不到协商。
            pendingRemoteSdp = isOffer to sdp
            logger.fault("[ice] ${System.identityHashCode(this)} pc 未就绪，缓存远端 SDP（start 后应用）")
            return
        }
        val type = if (isOffer) dev.onvoid.webrtc.RTCSdpType.OFFER else dev.onvoid.webrtc.RTCSdpType.ANSWER
        logger.fault("[ice] 调用 setRemoteDescription…")
        try {
            pc.setRemoteDescription(RTCSessionDescription(type, sdp), SetObserver("set-remote"))
            logger.fault("[ice] setRemoteDescription 返回（等回调）")
        } catch (failure: Throwable) {
            logger.fault("[ice] set-remote 解析失败: " + failure.message)
            observer?.onMediaFailed()
        }
    }

    override fun onRemoteCandidate(candidate: CallSignalBody.IceCandidate) {
        logger.fault("[ice] 远端候选 mid=${candidate.sdpMid} idx=${candidate.sdpMLineIndex}: ${candidate.candidate.take(100)}")
        val ice = RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, sanitizeCandidate(candidate.candidate))
        if (remoteDescriptionSet) {
            try {
                pc?.addIceCandidate(ice)
            } catch (failure: Throwable) {
                // 单条候选失败不终结呼叫：其余候选（srflx/relay）仍可完成连接
                logger.fault("[ice] addIceCandidate 失败 mid=${candidate.sdpMid}: " + failure.message)
            }
        } else {
            synchronized(bufferedRemoteCandidates) { bufferedRemoteCandidates += ice }
            logger.fault("[ice] 远端描述未就绪，候选进缓冲（当前 ${bufferedRemoteCandidates.size} 条）")
        }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        localAudioTrack?.setEnabled(!muted)
    }

    /** 桌面无听筒/扬声器之分；保留接口语义（扬声器常开）。 */
    override fun setSpeakerphone(enabled: Boolean) = Unit

    override fun switchCamera() = Unit

    /** 多摄像头外接时可切换；单个 USB 摄像头的桌面应隐藏切换入口。 */
    override val canSwitchCamera: Boolean by lazy {
        MediaDevices.getVideoCaptureDevices().size > 1
    }

    /** gather 完成或兜底到点后上送本地描述；两路并发时只有先到者生效。 */
    private fun deliverPendingLocalDescription() {
        val sdp = synchronized(negotiationLock) {
            val pending = pendingLocalSdp ?: return
            pendingLocalSdp = null
            pending
        }
        // 候选只经候选信号通道补传，不 munging 进 SDP：合并后的描述串在跨引擎
        // 解析下不可靠（Android 端曾因 ufrag 扩展解析失败）。
        logger.fault("[ice] 上送本地 SDP: ${sdp.sdpType}")
        observer?.onLocalDescription(sdp.sdpType == dev.onvoid.webrtc.RTCSdpType.OFFER, sdp.sdp)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        gatherFallbackExecutor.shutdownNow()
        // libwebrtc 原生引用计数要求先从 PeerConnection 摘除 sender，再逐层 dispose；
        // 逐项容错：销毁路径任何一步失败不中断其余回收（native 泄漏优于进程崩溃）。
        fun safely(action: () -> Unit) {
            runCatching(action)
        }
        safely { remoteSink?.let { sink -> remoteVideoTrack?.removeSink(sink) } }
        safely { probeChannel?.close() }
        pc?.let { connection ->
            safely { connection.senders.forEach(connection::removeTrack) }
            safely { connection.close() }
        }
        // 拆除顺序实证调整（真机 SIGABRT 崩溃报告）：旧顺序先 stop/dispose 采集源
        // 后 dispose 轨道，AVCaptureSession removeInput 与在途帧的 IOSurface 释放
        // 在 org.webrtc.cameravideocapturer.video 队列上竞争，malloc 空闲链损坏
        // 直接 abort。轨道（消费方）必须先于采集源（生产方）销毁；stop 之后留
        // 排水窗口让采集队列清完在途回调，再 dispose 源对象。
        safely { localVideoTrack?.setEnabled(false) }
        safely { localVideoTrack?.dispose() }
        safely { videoSource?.stop() }
        runCatching { Thread.sleep(CAPTURE_DRAIN_MILLIS) }
        safely { videoSource?.dispose() }
        safely { localAudioTrack?.dispose() }
        remoteVideo.value = null
        localVideo.value = null
        workExecutor.shutdown()
    }

    private fun startCamera(connection: RTCPeerConnection) {
        val devices = MediaDevices.getVideoCaptureDevices()
        val device = devices.firstOrNull()
        logger.fault("[call] 摄像头枚举到 ${devices.size} 个: " + devices.joinToString(", ") { it.getName() })
        if (device == null) {
            // 无摄像头（如无 USB 摄像头的台式 Mac）：绝不能用空设备启动视频源——
            // native 层得到坏轨后 answer 会丢掉整个视频 m-line，对端 m-line 数不
            // 匹配，整个连接建立失败（视频通话永远"连接中"的根因）。不加发送轨，
            // answer 对视频段回 recvonly，通话降级为只接收对方画面。
            logger.fault("[call] 本机无摄像头，视频降级为只接收")
            observer?.onLocalCameraStalled("本机未检测到摄像头，视频通话降级为只接收对方画面")
            return
        }
        val source = VideoDeviceSource()
        source.setVideoCaptureDevice(device)
        source.setVideoCaptureCapability(VideoCaptureCapability(1280, 720, 30))
        try {
            source.start()
        } catch (failure: Throwable) {
            // 启动即失败必须立刻可见，不允许静默黑屏（用户等待的正是明确的错误）
            logger.fault("[call] 摄像头启动失败: $failure")
            runCatching { source.dispose() }
            observer?.onLocalCameraStalled("摄像头启动失败：${failure.message ?: failure.javaClass.simpleName}")
            return
        }
        videoSource = source
        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        localVideoTrack = track
        connection.addTrack(track, listOf(STREAM_ID))
        track.addSink(FrameSink(isLocal = true))
        // 首帧看门狗：start() 成功≠有帧。macOS 对未授权/未签名进程的采集会话会
        // 静默拒绝（session 正常 running 但永远 0 帧，TCC 状态停在"未询问"），
        // 设备被占用同理。5 秒无首帧立即上报 UI，不再让用户对着黑屏猜。
        localVideoFirstFrame.set(false)
        gatherFallbackExecutor.schedule({
            if (!closed.get() && !localVideoFirstFrame.get()) {
                logger.fault("[call] 摄像头启动 5s 零帧（${device.getName()}），疑似系统权限拒绝或设备被占用")
                observer?.onLocalCameraStalled(
                    "本机摄像头无输出（可能是系统未授权摄像头权限，或设备被占用）；已降级为只发送语音",
                )
            }
        }, 5, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** 帧泵：I420 → BGRA 位图，~15fps 限频后在引擎线程推送。 */
    private inner class FrameSink(private val isLocal: Boolean) : VideoTrackSink {
        private var firstFrameLogged = false
        private var lastRotation = -1

        // 像素缓冲与位图跨帧复用：每帧 new Bitmap() 的 native 像素分配在 JVM GC 压力
        // 不足时永不回收（实测 1 小时通话堆积 200GB+）。复用后整个通话 native 分配 O(1)，
        // 分辨率变化时才重建。
        private var reuseBitmap: org.jetbrains.skia.Bitmap? = null
        private var reusePixels: ByteArray? = null
        private var reuseWidth = 0
        private var reuseHeight = 0

        override fun onVideoFrame(frame: VideoFrame) {
            val wasFirst = !firstFrameLogged
            // 归一化角度；方向翻转（用户旋转手机）单独记一条，便于排查"画面跟着转"
            val rotation = ((frame.rotation % 360) + 360) % 360
            if (wasFirst) {
                firstFrameLogged = true
                logger.fault(
                    "[ice] 首帧到达 isLocal=$isLocal ${frame.buffer.getWidth()}x${frame.buffer.getHeight()} rotation=$rotation",
                )
                if (isLocal) localVideoFirstFrame.set(true)
            } else if (rotation != lastRotation) {
                logger.fault("[ice] 帧方向变化 isLocal=$isLocal rotation=$rotation")
            }
            lastRotation = rotation
            val now = System.nanoTime()
            if (now - lastFramePublishNanos.get() < FRAME_INTERVAL_NANOS) return
            lastFramePublishNanos.set(now)
            try {
                val buffer = frame.buffer
                val width = buffer.getWidth()
                val height = buffer.getHeight()
                val outWidth = if (rotation == 90 || rotation == 270) height else width
                val outHeight = if (rotation == 90 || rotation == 270) width else height
                var bitmap = reuseBitmap
                var pixels = reusePixels
                if (bitmap == null || pixels == null || outWidth != reuseWidth || outHeight != reuseHeight) {
                    pixels = ByteArray(outWidth * outHeight * 4)
                    bitmap = org.jetbrains.skia.Bitmap()
                    bitmap.installPixels(
                        ImageInfo.makeN32(outWidth, outHeight, org.jetbrains.skia.ColorAlphaType.OPAQUE),
                        pixels,
                        outWidth * 4,
                    )
                    reuseBitmap = bitmap
                    reusePixels = pixels
                    reuseWidth = outWidth
                    reuseHeight = outHeight
                }
                convertI420ToBgra(buffer.toI420(), width, height, rotation, pixels)
                // installPixels 复用同一数组重绑，确保 Skia 侧指针刷新
                bitmap.installPixels(
                    ImageInfo.makeN32(outWidth, outHeight, org.jetbrains.skia.ColorAlphaType.OPAQUE),
                    pixels,
                    outWidth * 4,
                )
                if (isLocal) localVideo.value = DesktopVideoHandle(bitmap) else remoteVideo.value = DesktopVideoHandle(bitmap)
            } catch (failure: Exception) {
                if (!firstFrameLogged) logger.fault("[ice] 帧转换失败 isLocal=$isLocal: ${failure.message}")
                // 单帧转换失败只丢帧，不断媒体
            }
        }
    }

    private inner class CreateObserver(private val tag: String) : CreateSessionDescriptionObserver {
        override fun onSuccess(description: RTCSessionDescription) {
            pc?.setLocalDescription(description, SetObserver("set-local-$tag"))
        }

        override fun onFailure(error: String?) {
            logger.fault("通话 SDP $tag 失败: $error")
        }
    }

    private inner class SetObserver(private val tag: String) : SetSessionDescriptionObserver {
        override fun onSuccess() {
        logger.fault("[ice] SetObserver($tag).onSuccess 触发")
            val connection = pc ?: return
            when {
                tag.startsWith("set-local-") -> connection.localDescription?.let { local ->
                    var deliverNow = false
                    synchronized(negotiationLock) {
                        pendingLocalSdp = local
                        if (gatheringDone) {
                            pendingLocalSdp = null
                            deliverNow = true
                        }
                    }
                    if (deliverNow) {
                        observer?.onLocalDescription(local.sdpType == dev.onvoid.webrtc.RTCSdpType.OFFER, local.sdp)
                    } else {
                        logger.fault("SDP $tag 已设置，等待 gather 完成后上送（3s 兜底）")
                        gatherFallbackExecutor.schedule({
                            deliverPendingLocalDescription()
                        }, 3, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
                tag == "set-remote" -> {
                    remoteDescriptionSet = true
                    // 先取快照再清空：also{clear()} 会返回已清空的接收者，缓冲候选
                    // 被整体丢弃（真机"answer 完成但 ICE 永不 CHECKING"的根因）。
                    val buffered = synchronized(bufferedRemoteCandidates) {
                        val snapshot = bufferedRemoteCandidates.toList()
                        bufferedRemoteCandidates.clear()
                        snapshot
                    }
                    logger.fault("[ice] set-remote 完成，flush 缓冲候选 ${buffered.size} 条")
                    buffered.forEach { ice ->
                        try {
                            connection.addIceCandidate(ice)
                        } catch (failure: Throwable) {
                            logger.fault("[ice] flush addIceCandidate 抛错 mid=${ice.sdpMid}: ${failure.message}")
                        }
                    }
                    if (connection.remoteDescription?.sdpType == dev.onvoid.webrtc.RTCSdpType.OFFER) {
                        connection.createAnswer(RTCAnswerOptions(), CreateObserver("answer").also {
                            // createAnswer 成功后由 CreateObserver 设置本地描述
                        })
                    }
                }
            }
        }

        override fun onFailure(error: String?) {
            logger.fault("通话 SDP $tag 失败: $error")
        }
    }

    private inner class PeerObserver : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) {
            val sanitized = sanitizeCandidate(candidate.sdp)
            sentCandidates.incrementAndGet()
            logger.fault(
                "[ice] 本地候选(mid=${candidate.sdpMid},idx=${candidate.sdpMLineIndex},累计发送=${sentCandidates.get()}): " +
                    sanitized.take(110),
            )
            synchronized(negotiationLock) { gatheredCandidates.add(sanitized) }
            observer?.onLocalCandidate(
                CallSignalBody.IceCandidate(sanitized, candidate.sdpMid ?: "", candidate.sdpMLineIndex),
            )
        }

        override fun onIceGatheringChange(state: dev.onvoid.webrtc.RTCIceGatheringState) {
            logger.fault("ICE gather 状态: $state")
            if (state == dev.onvoid.webrtc.RTCIceGatheringState.COMPLETE) {
                synchronized(negotiationLock) { gatheringDone = true }
                deliverPendingLocalDescription()
            }
        }

        override fun onConnectionChange(state: RTCPeerConnectionState) {
            logger.fault("[ice] PeerConnection 状态: $state")
            when (state) {
                RTCPeerConnectionState.CONNECTED -> observer?.onMediaConnected()
                RTCPeerConnectionState.FAILED, RTCPeerConnectionState.CLOSED -> observer?.onMediaFailed()
                else -> Unit
            }
        }

        override fun onIceConnectionChange(state: RTCIceConnectionState) {
            logger.fault("[ice] ICE 连接状态: $state")
            when (state) {
                RTCIceConnectionState.CONNECTED, RTCIceConnectionState.COMPLETED -> observer?.onMediaConnected()
                RTCIceConnectionState.FAILED, RTCIceConnectionState.CLOSED -> observer?.onMediaFailed()
                else -> Unit
            }
        }

        override fun onTrack(transceiver: RTCRtpTransceiver) {
            val track = transceiver.receiver.getTrack()
            logger.fault("[ice] onTrack: kind=${track?.getKind()} state=${track?.getState()}")
            if (track is VideoTrack) {
                remoteVideoTrack = track
                remoteSink = FrameSink(isLocal = false).also { track.addSink(it) }
            }
        }

        override fun onAddTrack(receiver: RTCRtpReceiver, streams: Array<out MediaStream>) {
            logger.fault("[ice] onAddTrack: kind=${receiver.getTrack()?.getKind()}")
            (receiver.getTrack() as? VideoTrack)?.let { track ->
                remoteVideoTrack = track
                remoteSink = FrameSink(isLocal = false).also { track.addSink(it) }
            }
        }
    }

    companion object {
        private val sharedFactory: PeerConnectionFactory by lazy { PeerConnectionFactory() }
        private const val AUDIO_TRACK_ID = "call-audio"
        private const val VIDEO_TRACK_ID = "call-video"
        private const val STREAM_ID = "call-stream"
        private val FRAME_INTERVAL_NANOS = 66_000_000L

        /** 挂断排水窗口：capture stop 后等在途回调清空再 dispose，见 close() 注释。 */
        private const val CAPTURE_DRAIN_MILLIS = 300L

        /**
         * I420 → BGRA（BT.601 有限范围），原地写入复用缓冲 out。
         *
         * [rotation] 是帧携带的显示旋转（RTP CVO 透传，如手机竖屏拍摄=传感器横放
         * buffer + 90°/270°）：直接烤进像素映射，输出宽高按角度换位，不做第二趟
         * 旋转拷贝。90/270 的行主序遍历对 CPU cache 不友好，但 ~15fps 限频下
         * 720p 实测无压力，换来零额外分配。
         */
        fun convertI420ToBgra(buffer: I420Buffer, width: Int, height: Int, rotation: Int, out: ByteArray) {
            val y = buffer.getDataY()
            val u = buffer.getDataU()
            val v = buffer.getDataV()
            val strideY = buffer.getStrideY()
            val strideU = buffer.getStrideU()
            val strideV = buffer.getStrideV()
            val swap = rotation == 90 || rotation == 270
            val outWidth = if (swap) height else width
            val outHeight = if (swap) width else height
            val pixels = out
            var p = 0
            for (row in 0 until outHeight) {
                for (col in 0 until outWidth) {
                    // 输出像素 (col,row) 反查源平面坐标（srcCol,srcRow）
                    val srcCol: Int
                    val srcRow: Int
                    when (rotation) {
                        90 -> { srcCol = row; srcRow = height - 1 - col }          // 顺时针：源左上 → 输出右上
                        180 -> { srcCol = width - 1 - col; srcRow = height - 1 - row }
                        270 -> { srcCol = width - 1 - row; srcRow = col }          // 逆时针：源左上 → 输出左下
                        else -> { srcCol = col; srcRow = row }
                    }
                    val yp = y.get(srcRow * strideY + srcCol).toInt() and 0xFF
                    val up = (u.get((srcRow / 2) * strideU + srcCol / 2).toInt() and 0xFF) - 128
                    val vp = (v.get((srcRow / 2) * strideV + srcCol / 2).toInt() and 0xFF) - 128
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
        }
    }
}
