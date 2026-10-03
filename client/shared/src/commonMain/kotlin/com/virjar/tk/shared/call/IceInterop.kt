package com.virjar.tk.shared.call

/**
 * 双端引擎共享的 ICE 互操作契约（纯函数，commonTest 锁定行为）。
 *
 * 桌面 webrtc-java 与 Android Stream 发行版 libwebrtc 版本错位：候选行携带的
 * ufrag/network-id/network-cost 可选扩展会让旧版 libwebrtc 解析失败
 * （setRemoteDescription 整体报 "SessionDescription is NULL"、addIceCandidate
 * 静默丢弃）。两套引擎必须执行同一条剥离规则——这里历史上是复制粘贴两份，
 * 修一漏一（Android 侧缓冲 flush 的 also{clear()} 雷因此存活过整整一轮修复）。
 */

/** 剥离候选行里旧版 libwebrtc 无法解析的可选扩展；保留 raddr/rport/generation 等。 */
fun sanitizeIceCandidate(candidate: String): String =
    candidate.replace(Regex(" ufrag \\S+"), "")
        .replace(Regex(" network-id \\S+"), "")
        .replace(Regex(" network-cost \\S+"), "")
        .trim()

/**
 * 先快照再清空。`also { it.clear() }` 返回清空后的接收者，取到的永远是空表
 * （真机根因之一：整批缓冲候选被静默丢光，ICE 永不 CHECKING）。必须在调用方
 * 持有的锁内执行；本函数自身不做同步。
 */
fun <T> snapshotAndClear(buffer: MutableList<T>): List<T> {
    val snapshot = buffer.toList()
    buffer.clear()
    return snapshot
}

/** SDP 里 a=candidate 行计数；观测点：远端描述候选的应用进度。 */
fun countSdpCandidates(sdp: String): Int =
    sdp.lines().count { it.contains("a=candidate", ignoreCase = true) }
