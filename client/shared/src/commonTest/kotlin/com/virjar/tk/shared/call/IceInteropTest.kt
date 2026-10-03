package com.virjar.tk.shared.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 双端引擎 ICE 互操作契约的行为锁定：候选剥离规则、缓冲快照语义、候选计数。
 * 这里断言的是两个 libwebrtc 栈之间的线格式契约，任何变更都意味着互操作
 * 行为变化，必须显式改这里的期望。
 */
class IceInteropTest {

    @Test
    fun `剥离 ufrag network-id network-cost 扩展`() {
        val raw = "candidate:1 1 UDP 2122260223 192.168.1.4 52312 typ host " +
            "generation 0 ufrag 8Kh1 network-id 2 network-cost 50"
        assertEquals(
            "candidate:1 1 UDP 2122260223 192.168.1.4 52312 typ host generation 0",
            sanitizeIceCandidate(raw),
        )
    }

    @Test
    fun `干净候选原样保留`() {
        val clean = "candidate:1 1 UDP 2122260223 192.168.1.4 52312 typ host " +
            "raddr 0.0.0.0 rport 0 generation 0"
        assertEquals(clean, sanitizeIceCandidate(clean))
    }

    @Test
    fun `raddr rport generation 不受剥离影响`() {
        val raw = "candidate:2 1 TCP 2105458942 10.0.0.4 9 typ relay " +
            "raddr 10.0.0.4 rport 9 generation 3 ufrag AbCd"
        val out = sanitizeIceCandidate(raw)
        assertTrue(out.contains("raddr 10.0.0.4"), "raddr 应保留: $out")
        assertTrue(out.contains("rport 9"), "rport 应保留: $out")
        assertTrue(out.contains("generation 3"), "generation 应保留: $out")
        assertTrue(!out.contains("ufrag"), "ufrag 应剥离: $out")
    }

    @Test
    fun `快照返回全部元素并清空缓冲`() {
        val buffer = mutableListOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), snapshotAndClear(buffer))
        assertEquals(0, buffer.size, "清空后必须为空，否则后续 flush 静默丢候选")
    }

    @Test
    fun `空缓冲快照返回空表`() {
        val buffer = mutableListOf<Int>()
        assertEquals(emptyList(), snapshotAndClear(buffer))
    }

    @Test
    fun `SDP 候选行计数忽略大小写且不误计`() {
        val sdp = "v=0\r\n" +
            "a=candidate:1 1 UDP 1 192.168.1.4 52312 typ host\r\n" +
            "a=CANDIDATE:2 1 TCP 2 10.0.0.4 9 typ relay\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        assertEquals(2, countSdpCandidates(sdp))
    }
}
