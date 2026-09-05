package com.virjar.tk.shared.client

/** 稳定、进程无关的种子，仅用于在设备之间分散重连定时器。 */
internal fun stableReconnectJitterSeed(deviceId: String): UInt {
    var hash = FNV_OFFSET_BASIS
    deviceId.forEach { character ->
        hash = (hash xor character.code.toUInt()) * FNV_PRIME
    }
    return avalanche(hash)
}

/**
 * 封顶指数等量抖动。
 *
 * 第零次尝试等待 500..999 ms。之后的尝试保留指数形状，同时把客户端分散在每个固定延迟的上半段，
 * 封顶在 15,000..29,999 ms。
 */
internal fun reconnectRetryDelayMillis(
    retryIndex: Int,
    seed: UInt,
): Long {
    require(retryIndex >= 0) { "Reconnect retry index must not be negative" }
    val fixedDelay = minOf(
        MAX_RECONNECT_DELAY_MILLIS,
        INITIAL_RECONNECT_DELAY_MILLIS shl minOf(retryIndex, MAX_SHIFT),
    )
    val lowerBound = fixedDelay / 2L
    val spread = fixedDelay - lowerBound
    val mixed = avalanche(seed + GOLDEN_RATIO * (retryIndex.toUInt() + 1u))
    return lowerBound + (mixed % spread.toUInt()).toLong()
}

private fun avalanche(input: UInt): UInt {
    var value = input
    value = (value xor (value shr 16)) * 0x7feb352du
    value = (value xor (value shr 15)) * 0x846ca68bu
    return value xor (value shr 16)
}

private const val INITIAL_RECONNECT_DELAY_MILLIS = 1_000L
private const val MAX_RECONNECT_DELAY_MILLIS = 30_000L
private const val MAX_SHIFT = 5
private const val FNV_OFFSET_BASIS = 0x811c9dc5u
private const val FNV_PRIME = 0x01000193u
private const val GOLDEN_RATIO = 0x9e3779b9u
