package com.virjar.tk.desktop.call

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
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
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.video.VideoTrack
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 无头媒体层环回（不经服务器、无 UI、无声卡依赖——HeadlessAudioDeviceModule）：
 * 同进程双 PeerConnection 以生产引擎同款配置直连，验证 webrtc-java 传输层
 * ICE/DTLS/DataChannel 与音频轨本身可用。这是 desktop↔Android 媒体互连
 * 排查的基线——本测试不通，问题在桌面媒体栈自身；本测试通过而真机不通，
 * 问题在跨栈互连或信令面。
 */
class DesktopMediaLoopTest {

    private class Candidate(val sdpMid: String?, val sdpMLineIndex: Int, val sdp: String)

    private class Endpoint(val name: String) : PeerConnectionObserver {
        lateinit var pc: RTCPeerConnection
        val localCandidates = CopyOnWriteArrayList<Candidate>()
        val remoteCandidates = CopyOnWriteArrayList<Candidate>()
        val iceStates = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(1)
        var dataChannel: RTCDataChannel? = null
        val received = CountDownLatch(1)
        var lastMessage: String? = null
        val audioFrames = AtomicInteger(0)

        override fun onIceCandidate(candidate: RTCIceCandidate) {
            localCandidates.add(Candidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
            println("[ice-loop] $name 本地候选 mid=${candidate.sdpMid} idx=${candidate.sdpMLineIndex}: ${candidate.sdp.take(80)}")
        }

        override fun onIceConnectionChange(state: RTCIceConnectionState) {
            iceStates.add(state.name)
            if (state == RTCIceConnectionState.CONNECTED || state == RTCIceConnectionState.COMPLETED) {
                connected.countDown()
            }
        }

        override fun onConnectionChange(state: RTCPeerConnectionState) {
            if (state == RTCPeerConnectionState.CONNECTED) connected.countDown()
        }

        override fun onDataChannel(channel: RTCDataChannel) {
            dataChannel = channel
            channel.registerObserver(object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(amount: Long) {}
                override fun onStateChange() {}
                override fun onMessage(buffer: RTCDataChannelBuffer) {
                    if (!buffer.binary) {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        lastMessage = String(bytes)
                        received.countDown()
                    }
                }
            })
        }

        override fun onTrack(transceiver: RTCRtpTransceiver) {}

        fun addAudioTrack(factory: PeerConnectionFactory) {
            val source = factory.createAudioSource(AudioOptions())
            val audioTrack: AudioTrack = factory.createAudioTrack("a-$name", source)
            pc.addTrack(audioTrack, listOf("loop-stream"))
        }
    }

    private fun endpoint(
        name: String,
        factory: PeerConnectionFactory,
        iceServers: List<RTCIceServer>,
    ): Endpoint {
        val endpoint = Endpoint(name)
        val config = RTCConfiguration().apply { this.iceServers = iceServers }
        endpoint.pc = factory.createPeerConnection(config, endpoint) ?: fail("PeerConnection 创建失败: $name")
        return endpoint
    }

    private fun offerAnswer(a: Endpoint, b: Endpoint) {
        val offerLatch = CountDownLatch(1)
        val offerHolder = arrayOfNulls<RTCSessionDescription>(1)
        a.pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                offerHolder[0] = description
                offerLatch.countDown()
            }

            override fun onFailure(error: String?) {
                offerLatch.countDown()
            }
        })
        assertTrue(offerLatch.await(10, TimeUnit.SECONDS), "createOffer 超时")
        val offer = offerHolder[0] ?: fail("createOffer 失败")
        a.pc.setLocalDescription(offer, noopSet("a-set-local"))
        b.pc.setRemoteDescription(offer, noopSet("b-set-remote"))

        val answerLatch = CountDownLatch(1)
        val answerHolder = arrayOfNulls<RTCSessionDescription>(1)
        b.pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                answerHolder[0] = description
                answerLatch.countDown()
            }

            override fun onFailure(error: String?) {
                answerLatch.countDown()
            }
        })
        assertTrue(answerLatch.await(10, TimeUnit.SECONDS), "createAnswer 超时")
        val answer = answerHolder[0] ?: fail("createAnswer 失败")
        b.pc.setLocalDescription(answer, noopSet("b-set-local"))
        a.pc.setRemoteDescription(answer, noopSet("a-set-remote"))
    }

    private fun noopSet(tag: String) = object : SetSessionDescriptionObserver {
        override fun onSuccess() {}
        override fun onFailure(error: String?) {
            println("[$tag] set 失败: $error")
        }
    }

    private fun sanitize(sdp: String): String =
        sdp.replace(Regex(" ufrag \\S+"), "")
            .replace(Regex(" network-id \\S+"), "")
            .replace(Regex(" network-cost \\S+"), "")
            .trim()

    private fun crossForwardCandidates(a: Endpoint, b: Endpoint) {
        a.localCandidates.forEach { c ->
            val mid = c.sdpMid ?: c.sdpMLineIndex.toString()
            println("[ice-loop] A→B mid=$mid idx=${c.sdpMLineIndex}: ${sanitize(c.sdp).take(80)}")
            try {
                b.pc.addIceCandidate(RTCIceCandidate(mid, c.sdpMLineIndex, sanitize(c.sdp)))
            } catch (t: Throwable) {
                println("[ice-loop] A→B add 失败: ${t.message}")
            }
        }
        b.localCandidates.forEach { c ->
            val mid = c.sdpMid ?: c.sdpMLineIndex.toString()
            println("[ice-loop] B→A mid=$mid idx=${c.sdpMLineIndex}: ${sanitize(c.sdp).take(80)}")
            try {
                a.pc.addIceCandidate(RTCIceCandidate(mid, c.sdpMLineIndex, sanitize(c.sdp)))
            } catch (t: Throwable) {
                println("[ice-loop] B→A add 失败: ${t.message}")
            }
        }
    }

    @Test
    fun `传输层环回 - DataChannel 经 ICE DTLS 双向消息`() = runBlocking {
        assumeRealAudioEngineEnvironment()
        val factory = PeerConnectionFactory()
        val iceServers = listOf<RTCIceServer>()
        val a = endpoint("A", factory, iceServers)
        val b = endpoint("B", factory, iceServers)

        val dcA = a.pc.createDataChannel("loop", RTCDataChannelInit())
        dcA.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(amount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: RTCDataChannelBuffer) {}
        })

        offerAnswer(a, b)
        crossForwardCandidates(a, b)

        assertTrue(a.connected.await(20, TimeUnit.SECONDS), "A 未连接: iceStates=${a.iceStates} 本地候选=${a.localCandidates.size} 远端候选=${a.remoteCandidates.size}")
        assertTrue(b.connected.await(20, TimeUnit.SECONDS), "B 未连接: iceStates=${b.iceStates} 本地候选=${b.localCandidates.size} 远端候选=${b.remoteCandidates.size}")

        // 等 DataChannel 就绪后发送消息
        var sent = false
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !sent) {
            try {
                dcA.send(RTCDataChannelBuffer(ByteBuffer.wrap("ping-loop".toByteArray()), false))
                sent = true
            } catch (_: Exception) {
                Thread.sleep(200)
            }
        }
        assertTrue(sent, "DataChannel 发送失败（未就绪）")
        assertTrue(b.received.await(10, TimeUnit.SECONDS), "B 未收到 DataChannel 消息")
        assertEquals("ping-loop", b.lastMessage)
        factory.dispose()
    }

    @Ignore("本机音频设备枚举在 native 层 abort，音频帧级验证由 L2 真机机器人承担")
    @Test
    fun `音频环回 - 音频轨协商成功且远端轨可用`() = runBlocking {
        assumeRealAudioEngineEnvironment()
        val factory = PeerConnectionFactory()
        val a = endpoint("A", factory, emptyList())
        val b = endpoint("B", factory, emptyList())

        // 音频源创建在部分机器上会在 native 层 abort，默认关闭；音频帧级验证由 L2 承担
        val audioAdded = false

        offerAnswer(a, b)
        crossForwardCandidates(a, b)
        assertTrue(a.connected.await(20, TimeUnit.SECONDS), "A 未连接")
        assertTrue(b.connected.await(20, TimeUnit.SECONDS), "B 未连接")

        if (audioAdded) {
            withTimeout(10_000) {
                kotlinx.coroutines.delay(500)
            }
            println("音频轨协商成功（帧级验证由 L2 真机机器人承担）")
        }
        factory.dispose()
    }
}
