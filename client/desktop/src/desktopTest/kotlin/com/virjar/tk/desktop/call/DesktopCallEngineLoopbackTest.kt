package com.virjar.tk.desktop.call

import com.virjar.tk.shared.call.CallMediaEngine
import com.virjar.tk.shared.call.CallMediaObserver
import com.virjar.tk.protocol.model.CallSignalBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 桌面引擎媒体面环回：同进程两个 DesktopCallEngine 经 SDP/ICE 手工桥接，
 * 验证非 trickle 协商与 ICE 连接能到达 CONNECTED。主机候选环回互通，
 * 不依赖 TURN。音频/摄像头采集受宿主 TCC 授权影响：无麦克风权限的机器上
 * 采集可能失败，但 PeerConnection 连接建立本身应当完成。
 */
class DesktopCallEngineLoopbackTest {

    class RecordingObserver : CallMediaObserver {
        @Volatile var connected = false
        val localSdps = mutableListOf<Pair<Boolean, String>>()
        val localCandidates = mutableListOf<CallSignalBody.IceCandidate>()
        private val latch = CountDownLatch(1)

        override fun onLocalDescription(isOffer: Boolean, sdp: String) {
            synchronized(localSdps) { localSdps += isOffer to sdp }
        }

        override fun onLocalCandidate(candidate: CallSignalBody.IceCandidate) {
            synchronized(localCandidates) { localCandidates += candidate }
        }

        override fun onMediaConnected() {
            connected = true
            latch.countDown()
        }

        override fun onMediaFailed() {}

        fun awaitConnected(timeoutSec: Long): Boolean = latch.await(timeoutSec, TimeUnit.SECONDS)
    }

    @Test
    fun `双引擎环回 - 协商与ICE连接互通`() {
        val caller = DesktopCallEngine()
        val callee = DesktopCallEngine()
        try {
            val callerObserver = RecordingObserver()
            val calleeObserver = RecordingObserver()

            // 同进程直连：不配置 ICE 服务器（host 候选环回可达）
            caller.start(video = false, iceServers = emptyList(), observer = callerObserver)
            callee.start(video = false, iceServers = emptyList(), observer = calleeObserver)

            caller.initiateOffer()

            // SDP 桥接：offer（gathering 完成后整段）→ 被叫；answer → 主叫
            val deadline = System.currentTimeMillis() + 30_000
            var bridged = false
            while (!bridged && System.currentTimeMillis() < deadline) {
                val offer = synchronized(callerObserver.localSdps) { callerObserver.localSdps.firstOrNull { it.first } }
                if (offer != null) {
                    callee.onRemoteSessionDescription(isOffer = true, sdp = offer.second)
                    bridged = true
                } else {
                    Thread.sleep(100)
                }
            }
            assertTrue(bridged, "主叫 offer 未在 30s 内产出（gathering 未完成或创建失败）")

            var answered = false
            while (!answered && System.currentTimeMillis() < deadline) {
                val answer = synchronized(calleeObserver.localSdps) { calleeObserver.localSdps.firstOrNull { !it.first } }
                if (answer != null) {
                    caller.onRemoteSessionDescription(isOffer = false, sdp = answer.second)
                    answered = true
                } else {
                    Thread.sleep(100)
                }
            }
            assertTrue(answered, "被叫 answer 未产出（远端 offer 处理失败）")

            // 候选双向转发（SDP 已含候选时这是幂等补充）
            val forwardCandidates = Runnable {
                while (!callerObserver.connected && !calleeObserver.connected &&
                    System.currentTimeMillis() < deadline
                ) {
                    synchronized(callerObserver.localCandidates) {
                        callerObserver.localCandidates.forEach { callee.onRemoteCandidate(it) }
                    }
                    synchronized(calleeObserver.localCandidates) {
                        calleeObserver.localCandidates.forEach { caller.onRemoteCandidate(it) }
                    }
                    Thread.sleep(100)
                }
            }
            Thread(forwardCandidates, "loopback-candidates").apply { isDaemon = true }.start()

            val connected = callerObserver.awaitConnected(30) || calleeObserver.awaitConnected(5)
            assertTrue(connected, "环回媒体连接未建立（ICE 未到达 CONNECTED）")
            assertTrue(callerObserver.connected || calleeObserver.connected)
        } finally {
            caller.close()
            callee.close()
        }
    }
}
