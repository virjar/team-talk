package com.virjar.tk.desktop.call

import com.virjar.tk.protocol.model.CallSignalBody
import dev.onvoid.webrtc.PeerConnectionFactory
import com.virjar.tk.shared.call.CallMediaEngine
import com.virjar.tk.shared.call.CallMediaObserver
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L1 媒体引擎环回：两个**生产 [DesktopCallEngine]** 经内存信令总线对接。
 *
 * 与 DesktopMediaLoopTest（裸 RTCPeerConnection，只证 libwebrtc 本身）不同，
 * 本层测的是引擎封装的全部协商逻辑：候选收集→sanitize→信令发送→对端应用、
 * SDP 时序（gather 兜底）、音频/视频轨的建立。历史上所有真机媒体故障
 * （候选带 ufrag 被对端拒、候选一条未发、视频 m-line 丢失）都发生在这层，
 * 都应被本测试拦截。
 *
 * 预期：A initiateOffer 后双方在数秒内 onMediaConnected，双向候选计数 >0，
 * 且所有经过信令总线的候选不含 ufrag/network-id/network-cost 扩展。
 */
class DesktopEngineLinkupTest {

    private val logger = PlatformOnlyTkLogger("EngineLinkup")

    /** 内存信令总线：把一端的本地描述/候选立即投递给对端，模拟 CALL_SIGNAL 通道。 */
    private class SignalingBus {
        val aToBCandidates = AtomicInteger(0)
        val bToACandidates = AtomicInteger(0)
        val sanitizedViolations = CopyOnWriteArrayList<String>()

        fun checkSanitized(candidate: CallSignalBody.IceCandidate, direction: String) {
            listOf(" ufrag ", " network-id ", " network-cost ").forEach { marker ->
                if (candidate.candidate.contains(marker)) {
                    sanitizedViolations.add("$direction 含未剥离扩展 [$marker]: ${candidate.candidate.take(90)}")
                }
            }
        }
    }

    private inner class HarnessSide(val name: String) {
        lateinit var engine: DesktopCallEngine
        var peer: HarnessSide? = null
        val mediaConnected = CountDownLatch(1)
        val mediaFailed = CopyOnWriteArrayList<String>()
        val remoteCandidatesReceived = AtomicInteger(0)
        var peerRef: HarnessSide? = null
        var bus: SignalingBus? = null

        val observer = object : CallMediaObserver {
            override fun onLocalDescription(isOffer: Boolean, sdp: String) {
                logger.fault("[$name] 本地描述 offer=$isOffer len=${sdp.length} → 转发对端")
                try {
                    peer?.engine?.onRemoteSessionDescription(isOffer, sdp)
                    
                } catch (failure: Throwable) {
                    logger.fault("[$name] 转发描述失败: ${failure.javaClass.simpleName}: ${failure.message}")
                    mediaFailed.add("转发描述失败: ${failure.message}")
                }
            }

            override fun onLocalCandidate(candidate: CallSignalBody.IceCandidate) {
                val count = if (name == "A") bus!!.aToBCandidates.incrementAndGet() else bus!!.bToACandidates.incrementAndGet()
                bus!!.checkSanitized(candidate, name)
                logger.fault("[$name] 候选 #$count mid=${candidate.sdpMid} → 转发对端")
                try {
                    peerRef?.remoteCandidatesReceived?.incrementAndGet()
                    peer?.engine?.onRemoteCandidate(candidate)
                } catch (failure: Throwable) {
                    logger.fault("[$name] 转发候选失败: ${failure.javaClass.simpleName}: ${failure.message}")
                    mediaFailed.add("转发候选失败: ${failure.message}")
                }
            }

            override fun onMediaConnected() {
                logger.fault("[$name] 媒体已接通")
                mediaConnected.countDown()
            }

            override fun onMediaFailed() {
                logger.fault("[$name] 媒体失败回调")
                mediaFailed.add("onMediaFailed")
            }
        }
    }

    private fun wire(bus: SignalingBus, video: Boolean): Pair<HarnessSide, HarnessSide> {
        val a = HarnessSide("A")
        val b = HarnessSide("B")
        a.peer = b
        b.peer = a
        a.peerRef = b
        b.peerRef = a
        a.bus = bus
        b.bus = bus
        a.engine = DesktopCallEngine()
        b.engine = DesktopCallEngine()
        // 本地环回：无需 STUN/TURN，host 候选直连
        a.engine.start(video, emptyList(), a.observer)
        b.engine.start(video, emptyList(), b.observer)
        return a to b
    }

    @Test
    fun `L1半对接 - 引擎A与裸PC-B`() = runBlocking {
        val engineA = DesktopCallEngine()
        val states = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(2)
        var engineConnected = false

        val factory = PeerConnectionFactory()
        val config = dev.onvoid.webrtc.RTCConfiguration()
        var bareB: dev.onvoid.webrtc.RTCPeerConnection? = null
        val bareBFactory = PeerConnectionFactory()
        bareB = bareBFactory.createPeerConnection(config, object : dev.onvoid.webrtc.PeerConnectionObserver {
            override fun onIceCandidate(candidate: dev.onvoid.webrtc.RTCIceCandidate) {
                try {
                    engineA.onRemoteCandidate(
                        com.virjar.tk.protocol.model.CallSignalBody.IceCandidate(
                            candidate.sdp, candidate.sdpMid ?: "", candidate.sdpMLineIndex,
                        ),
                    )
                } catch (failure: Throwable) {
                    println("bareB→A 候选转发失败: ${failure.message}")
                }
            }
            override fun onIceConnectionChange(state: dev.onvoid.webrtc.RTCIceConnectionState) {
                states.add("bare:$state")
                if (state == dev.onvoid.webrtc.RTCIceConnectionState.CONNECTED) connected.countDown()
            }
        }) ?: error("bare B 创建失败")

        bareB.addTrack(factory.createAudioTrack("b", factory.createAudioSource(dev.onvoid.webrtc.media.audio.AudioOptions())), listOf("s"))

        engineA.start(false, emptyList(), object : CallMediaObserver {
            override fun onLocalDescription(isOffer: Boolean, sdp: String) {
                println("engineA 本地描述 offer=$isOffer → bare")
                bareB.setRemoteDescription(dev.onvoid.webrtc.RTCSessionDescription(
                    if (isOffer) dev.onvoid.webrtc.RTCSdpType.OFFER else dev.onvoid.webrtc.RTCSdpType.ANSWER, sdp),
                    object : dev.onvoid.webrtc.SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            if (isOffer) {
                                bareB.createAnswer(dev.onvoid.webrtc.RTCAnswerOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
                                    override fun onSuccess(description: dev.onvoid.webrtc.RTCSessionDescription) {
                                        bareB.setLocalDescription(description, object : dev.onvoid.webrtc.SetSessionDescriptionObserver {
                                            override fun onSuccess() {}
                                            override fun onFailure(error: String?) {}
                                        })
                                        engineA.onRemoteSessionDescription(false, description.sdp)
                                    }
                                    override fun onFailure(error: String?) { println("bare answer 失败: $error") }
                                })
                            }
                        }
                        override fun onFailure(error: String?) { println("bare set-remote 失败: $error") }
                    })
            }
            override fun onLocalCandidate(candidate: com.virjar.tk.protocol.model.CallSignalBody.IceCandidate) {
                try {
                    bareB.addIceCandidate(dev.onvoid.webrtc.RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate))
                } catch (failure: Throwable) {
                    println("A→bare 候选转发失败: ${failure.message}")
                }
            }
            override fun onMediaConnected() {
                engineConnected = true
                println("engineA 媒体接通")
                connected.countDown()
            }
            override fun onMediaFailed() { println("engineA 媒体失败") }
        })

        engineA.initiateOffer()
        val ok = connected.await(25, TimeUnit.SECONDS)
        println("L1-half(engineA×bareB): connected=$ok engineConnected=$engineConnected states=$states")
        assertTrue(ok, "引擎×裸也不通 → 问题在引擎内部。states=$states")
        engineA.close()
    }

    // 视频用例=真机 set-remote 回调死的本机复现器，保持启用
    @Test
    fun `L1 视频链路 - 有摄像头时双向视频接通`() = runBlocking {
        val hasCamera = try {
            dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices().isNotEmpty()
        } catch (failure: Throwable) {
            false
        }
        if (!hasCamera) {
            logger.fault("本机 AVFoundation 无相机（USB 相机未注册），视频环回跳过——由 L1 音频 + 摄像头枚举诊断覆盖")
            return@runBlocking
        }

        val bus = SignalingBus()
        val (a, b) = wire(bus, video = true)
        a.engine.initiateOffer()

        val connected = a.mediaConnected.await(25, TimeUnit.SECONDS) && b.mediaConnected.await(25, TimeUnit.SECONDS)
        assertTrue(connected, "视频媒体未接通")
        assertTrue(bus.aToBCandidates.get() > 0 && bus.bToACandidates.get() > 0, "候选发送链路断裂")
        assertEquals(emptyList(), bus.sanitizedViolations.toList())

        a.engine.close()
        b.engine.close()
    }
}
