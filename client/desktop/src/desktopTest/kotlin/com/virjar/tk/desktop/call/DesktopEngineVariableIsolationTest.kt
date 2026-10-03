package com.virjar.tk.desktop.call

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.AudioOptions
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 系统性诊断二分：裸 PC 基线（DesktopMediaLoopTest）能通，生产引擎对接
 * （DesktopEngineLinkupTest）不通。两个差异变量逐一隔离：
 *  A. 音频轨（双端各自的 ADM 争抢系统音频设备）
 *  B. 双 PeerConnectionFactory（webrtc-java native 全局状态冲突）
 */
/** webrtc-java 的 factory dispose 会毒化进程全局状态：先跑的测试 dispose 后，
 *  后续任何测试的 ICE 全部失效（与生产引擎的同构教训）。全部测试共享、永不 dispose。 */
object SharedTestFactory {
    private val factory: PeerConnectionFactory by lazy { PeerConnectionFactory() }
    fun get(): PeerConnectionFactory = factory
}

class DesktopEngineVariableIsolationTest {

    private val peers = mutableMapOf<String, dev.onvoid.webrtc.RTCPeerConnection>()

    private fun pc(factory: PeerConnectionFactory, name: String, states: CopyOnWriteArrayList<String>, connected: CountDownLatch): dev.onvoid.webrtc.RTCPeerConnection {
        val config = dev.onvoid.webrtc.RTCConfiguration()
        val pc = factory.createPeerConnection(config, object : dev.onvoid.webrtc.PeerConnectionObserver {
            override fun onIceCandidate(candidate: dev.onvoid.webrtc.RTCIceCandidate) {
                try {
                    peers[name + "-peer"]?.addIceCandidate(
                        dev.onvoid.webrtc.RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp),
                    )
                } catch (failure: Throwable) {
                    println("$name→peer 候选转发失败: ${failure.message}")
                }
            }
            override fun onIceConnectionChange(state: dev.onvoid.webrtc.RTCIceConnectionState) {
                states.add("$name:${state.name}")
                if (state == dev.onvoid.webrtc.RTCIceConnectionState.CONNECTED || state == dev.onvoid.webrtc.RTCIceConnectionState.COMPLETED) connected.countDown()
            }
        }) ?: error("pc 创建失败")
        return pc
    }

    private fun wire(a: dev.onvoid.webrtc.RTCPeerConnection, b: dev.onvoid.webrtc.RTCPeerConnection) {
        // 由调用方写入 peers 映射；这里通过名字约定在创建时绑定
    }

    private fun negotiate(a: dev.onvoid.webrtc.RTCPeerConnection, b: dev.onvoid.webrtc.RTCPeerConnection) {
        val offerLatch = CountDownLatch(1)
        var offer: dev.onvoid.webrtc.RTCSessionDescription? = null
        a.createOffer(dev.onvoid.webrtc.RTCOfferOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
            override fun onSuccess(description: dev.onvoid.webrtc.RTCSessionDescription) { offer = description; offerLatch.countDown() }
            override fun onFailure(error: String?) { offerLatch.countDown() }
        })
        offerLatch.await(10, TimeUnit.SECONDS)
        a.setLocalDescription(offer!!, object : dev.onvoid.webrtc.SetSessionDescriptionObserver {
            override fun onSuccess() {}
            override fun onFailure(error: String?) {}
        })
        b.setRemoteDescription(offer!!, object : dev.onvoid.webrtc.SetSessionDescriptionObserver {
            override fun onSuccess() {}
            override fun onFailure(error: String?) { println("B set-remote 失败: $error") }
        })
        val answerLatch = CountDownLatch(1)
        var answer: dev.onvoid.webrtc.RTCSessionDescription? = null
        b.createAnswer(dev.onvoid.webrtc.RTCAnswerOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
            override fun onSuccess(description: dev.onvoid.webrtc.RTCSessionDescription) { answer = description; answerLatch.countDown() }
            override fun onFailure(error: String?) { answerLatch.countDown() }
        })
        answerLatch.await(10, TimeUnit.SECONDS)
        b.setLocalDescription(answer!!, object : dev.onvoid.webrtc.SetSessionDescriptionObserver {
            override fun onSuccess() {}
            override fun onFailure(error: String?) {}
        })
        a.setRemoteDescription(answer!!, object : dev.onvoid.webrtc.SetSessionDescriptionObserver {
            override fun onSuccess() {}
            override fun onFailure(error: String?) { println("A set-remote 失败: $error") }
        })
    }

    @Test
    fun `变量A - 共享factory加音频轨`() = runBlocking {
        assumeRealAudioEngineEnvironment()
        val factory = SharedTestFactory.get()
        val states = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(2)
        val a = pc(factory, "A", states, connected)
        val b = pc(factory, "B", states, connected)
        peers["A-peer"] = b; peers["B-peer"] = a
        a.addTrack(factory.createAudioTrack("a", factory.createAudioSource(AudioOptions())), listOf("s"))
        b.addTrack(factory.createAudioTrack("b", factory.createAudioSource(AudioOptions())), listOf("s"))
        negotiate(a, b)
        val ok = connected.await(20, TimeUnit.SECONDS)
        println("VAR-A(共享factory+音频轨): connected=$ok states=$states")
        assertTrue(ok, "共享 factory + 音频轨不通 → 音频轨/ADM 是根因。states=$states")
    }

    @Test
    fun `变量F - E基础上候选剥离ufrag`() = runBlocking {
        assumeRealAudioEngineEnvironment()
        val factory = SharedTestFactory.get()
        val states = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(2)
        val sanitize = { sdp: String ->
            sdp.replace(Regex(" ufrag \\S+"), "").replace(Regex(" network-id \\S+"), "").replace(Regex(" network-cost \\S+"), "").trim()
        }
        val a = pcSanitized(factory, "A", states, connected, sanitize)
        val b = pcSanitized(factory, "B", states, connected, sanitize)
        peers["A-peer"] = b; peers["B-peer"] = a
        a.createDataChannel("probe", dev.onvoid.webrtc.RTCDataChannelInit())
        a.addTrack(factory.createAudioTrack("a", factory.createAudioSource(AudioOptions())), listOf("s"))
        b.addTrack(factory.createAudioTrack("b", factory.createAudioSource(AudioOptions())), listOf("s"))
        negotiate(a, b)
        val ok = connected.await(20, TimeUnit.SECONDS)
        println("VAR-F(E+sanitize): connected=$ok states=$states")
        assertTrue(ok, "sanitize 剥 ufrag 后同栈不通 → sanitize 对 webrtc-java 自身有毒。states=$states")
    }

    private fun pcSanitized(factory: PeerConnectionFactory, name: String, states: CopyOnWriteArrayList<String>, connected: CountDownLatch, sanitize: (String) -> String): dev.onvoid.webrtc.RTCPeerConnection {
        val config = dev.onvoid.webrtc.RTCConfiguration()
        return factory.createPeerConnection(config, object : dev.onvoid.webrtc.PeerConnectionObserver {
            override fun onIceCandidate(candidate: dev.onvoid.webrtc.RTCIceCandidate) {
                try {
                    val sanitized = sanitize(candidate.sdp)
                    peers[name + "-peer"]?.addIceCandidate(dev.onvoid.webrtc.RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, sanitized))
                } catch (failure: Throwable) {
                    println("VAR-F $name 转发失败: ${failure.message}")
                }
            }
            override fun onIceConnectionChange(state: dev.onvoid.webrtc.RTCIceConnectionState) {
                states.add("$name:${state.name}")
                if (state == dev.onvoid.webrtc.RTCIceConnectionState.CONNECTED || state == dev.onvoid.webrtc.RTCIceConnectionState.COMPLETED) connected.countDown()
            }
        }) ?: error("pc 创建失败")
    }

@Test
    fun `变量E - 默认ADM加音频轨加DataChannel`() = runBlocking {
        assumeRealAudioEngineEnvironment()
        val factory = SharedTestFactory.get()
        val states = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(2)
        val a = pc(factory, "A", states, connected)
        val b = pc(factory, "B", states, connected)
        peers["A-peer"] = b; peers["B-peer"] = a
        val dc = a.createDataChannel("probe", dev.onvoid.webrtc.RTCDataChannelInit())
        dc.registerObserver(object : dev.onvoid.webrtc.RTCDataChannelObserver {
            override fun onBufferedAmountChange(amount: Long) {}
            override fun onStateChange() { println("VAR-E DC state: ${dc.getState()}") }
            override fun onMessage(buffer: dev.onvoid.webrtc.RTCDataChannelBuffer) {}
        })
        a.addTrack(factory.createAudioTrack("a", factory.createAudioSource(AudioOptions())), listOf("s"))
        b.addTrack(factory.createAudioTrack("b", factory.createAudioSource(AudioOptions())), listOf("s"))
        negotiate(a, b)
        val ok = connected.await(20, TimeUnit.SECONDS)
        println("VAR-E(默认ADM+音频轨+DC): connected=$ok states=$states")
        assertTrue(ok, "默认 ADM + 音频轨 + DC 不通。states=$states")
    }

    @Test
    fun `变量B - 双factory纯DataChannel`() = runBlocking {
        assumeRealAudioEngineEnvironment()
        val fa = SharedTestFactory.get()
        val fb = SharedTestFactory.get()
        val states = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(2)
        val a = pc(fa, "A", states, connected)
        val b = pc(fb, "B", states, connected)
        peers["A-peer"] = b; peers["B-peer"] = a
        a.createDataChannel("probe", dev.onvoid.webrtc.RTCDataChannelInit())
        negotiate(a, b)
        val ok = connected.await(20, TimeUnit.SECONDS)
        println("VAR-B(双factory+DC): connected=$ok states=$states")
        assertTrue(ok, "双 factory + DC 不通 → webrtc-java 双 factory 是根因。states=$states")
    }
@Test
    fun `变量G - F基础上仿真引擎时序-候选缓冲flush加offer延迟`() = runBlocking {
        assumeRealAudioEngineEnvironment()
        val factory = SharedTestFactory.get()
        val states = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(2)
        // 引擎时序仿真：SDP 延迟到 gather 完成后交换；候选先到则缓冲、set-remote 成功后 flush
        val pendingForB = CopyOnWriteArrayList<dev.onvoid.webrtc.RTCIceCandidate>()
        val pendingForA = CopyOnWriteArrayList<dev.onvoid.webrtc.RTCIceCandidate>()
        var bRemoteSet = false
        var aRemoteSet = false

        fun makeSide(name: String): dev.onvoid.webrtc.RTCPeerConnection {
            val config = dev.onvoid.webrtc.RTCConfiguration()
            return factory.createPeerConnection(config, object : dev.onvoid.webrtc.PeerConnectionObserver {
                override fun onIceCandidate(candidate: dev.onvoid.webrtc.RTCIceCandidate) {
                    val target = if (name == "A") pendingForB else pendingForA
                    val peerReady = if (name == "A") bRemoteSet else aRemoteSet
                    if (peerReady) {
                        peers[name + "-peer"]?.addIceCandidate(candidate)
                    } else {
                        target.add(candidate)
                    }
                }
                override fun onIceConnectionChange(state: dev.onvoid.webrtc.RTCIceConnectionState) {
                    states.add("$name:${state.name}")
                    if (state == dev.onvoid.webrtc.RTCIceConnectionState.CONNECTED || state == dev.onvoid.webrtc.RTCIceConnectionState.COMPLETED) connected.countDown()
                }
            }) ?: error("pc 创建失败")
        }

        val a = makeSide("A")
        val b = makeSide("B")
        peers["A-peer"] = b; peers["B-peer"] = a
        a.createDataChannel("probe", dev.onvoid.webrtc.RTCDataChannelInit())
        b.createDataChannel("probe-b-answerer", dev.onvoid.webrtc.RTCDataChannelInit()) // 引擎行为：answerer 也建 probe DC
        a.addTrack(factory.createAudioTrack("a", factory.createAudioSource(AudioOptions())), listOf("s"))
        b.addTrack(factory.createAudioTrack("b", factory.createAudioSource(AudioOptions())), listOf("s"))

        // offer/answer 交换（引擎语义：set-remote 完成后 flush 缓冲候选）
        val offerLatch = CountDownLatch(1)
        var offer: dev.onvoid.webrtc.RTCSessionDescription? = null
        a.createOffer(dev.onvoid.webrtc.RTCOfferOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
            override fun onSuccess(description: dev.onvoid.webrtc.RTCSessionDescription) { offer = description; offerLatch.countDown() }
            override fun onFailure(error: String?) { offerLatch.countDown() }
        })
        offerLatch.await(10, TimeUnit.SECONDS)
        a.setLocalDescription(offer!!, object : dev.onvoid.webrtc.SetSessionDescriptionObserver {
            override fun onSuccess() {}
            override fun onFailure(error: String?) {}
        })
        Thread.sleep(300) // gather 让候选先流动（引擎里候选先于 SDP 到达对端）
        b.setRemoteDescription(offer!!, object : dev.onvoid.webrtc.SetSessionDescriptionObserver {
            override fun onSuccess() {
                bRemoteSet = true
                pendingForB.forEach { b.addIceCandidate(it) }
            }
            override fun onFailure(error: String?) { println("B set-remote 失败: $error") }
        })
        val answerLatch = CountDownLatch(1)
        var answer: dev.onvoid.webrtc.RTCSessionDescription? = null
        b.createAnswer(dev.onvoid.webrtc.RTCAnswerOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
            override fun onSuccess(description: dev.onvoid.webrtc.RTCSessionDescription) { answer = description; answerLatch.countDown() }
            override fun onFailure(error: String?) { answerLatch.countDown() }
        })
        answerLatch.await(10, TimeUnit.SECONDS)
        b.setLocalDescription(answer!!, object : dev.onvoid.webrtc.SetSessionDescriptionObserver {
            override fun onSuccess() {}
            override fun onFailure(error: String?) {}
        })
        a.setRemoteDescription(answer!!, object : dev.onvoid.webrtc.SetSessionDescriptionObserver {
            override fun onSuccess() {
                aRemoteSet = true
                pendingForA.forEach { a.addIceCandidate(it) }
            }
            override fun onFailure(error: String?) { println("A set-remote 失败: $error") }
        })

        val ok = connected.await(20, TimeUnit.SECONDS)
        println("VAR-G(引擎时序仿真): connected=$ok states=$states pendingA=${pendingForA.size} pendingB=${pendingForB.size}")
        assertTrue(ok, "引擎时序（缓冲+延迟）下不通。states=$states")
    }


}
