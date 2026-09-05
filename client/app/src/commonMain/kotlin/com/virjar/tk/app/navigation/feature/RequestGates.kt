package com.virjar.tk.app.navigation.feature

import com.virjar.tk.shared.AppError

/**
 * 异步 UI 请求的同步 generation gate。
 *
 * 在第一个挂起点之前调用 [begin]。只有最近的 token 可以提交状态，而且它的
 * key 也记录拥有该响应的 route/实体身份。
 */
internal class LatestRequestGate<K> {
    internal data class Token<K>(val generation: Long, val target: K)

    private var generation = 0L
    private var current: Token<K>? = null

    fun begin(key: K): Token<K> {
        // 在推进之前退役前一个能力，这样耗尽也会 fail closed。
        current = null
        return Token(nextGeneration(), key).also { current = it }
    }

    fun isCurrent(token: Token<K>): Boolean = current == token

    fun targets(key: K): Boolean = current?.target == key

    fun invalidate() {
        current = null
        nextGeneration()
    }

    private fun nextGeneration(): Long {
        check(generation < Long.MAX_VALUE) { "Latest request generation exhausted" }
        generation += 1L
        return generation
    }
}

/** 用于目标身份分开存储的工作流的更轻 generation gate。 */
internal class GenerationGate {
    private var current = 0L
    private var exhausted = false

    fun next(): Long {
        if (current == Long.MAX_VALUE) {
            exhausted = true
            current = 0L
            error("Request generation exhausted")
        }
        check(!exhausted) { "Request generation exhausted" }
        current += 1L
        return current
    }

    fun isCurrent(generation: Long): Boolean = !exhausted && generation == current
}

/** 当持久投影已经可用时，离线/超时刷新是预期内的。 */
internal fun shouldReportCacheRefreshFailure(error: Throwable, hasLocalProjection: Boolean): Boolean =
    !hasLocalProjection || (error != AppError.Network && error != AppError.Timeout)
