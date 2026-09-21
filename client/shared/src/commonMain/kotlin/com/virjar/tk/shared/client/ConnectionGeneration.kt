package com.virjar.tk.shared.client

import kotlin.concurrent.Volatile

/** EventLoop 持有的单调门禁；过期尝试回调只能比较，绝不能变更。 */
internal class ConnectionGeneration {
    @Volatile
    var current: Long = 0L
        private set

    fun next(): Long {
        check(current < Long.MAX_VALUE) { "Connection generation exhausted" }
        current += 1
        return current
    }

    fun invalidate() {
        check(current < Long.MAX_VALUE) { "Connection generation exhausted" }
        current += 1
    }

    fun matches(candidate: Long): Boolean = candidate == current
}
