package com.virjar.tk.server.domain.bot

/**
 * 带有严格密钥上限的进程本地固定窗口限流器。
 *
 * 服务器目前以单实例运行，因此这个限流器刻意本地化。过期的窗口会被机会性地移除；
 * 当每个被追踪的密钥都仍然活跃时，未见过的密钥会被拒绝，而不是无限制地增长内存。
 * 未来的多实例部署必须把这个策略移到共享的/事务性的限流端口之后。
 */
internal class BoundedFixedWindowRateLimiter<K : Any>(
    private val limit: Int,
    private val windowMillis: Long,
    private val maxTrackedKeys: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Window(val expiresAt: Long, val count: Int)

    private val lock = Any()
    private val windows = LinkedHashMap<K, Window>()
    private var operations = 0

    init {
        require(limit in 1 until Int.MAX_VALUE) { "rate limit must be positive and bounded" }
        require(windowMillis > 0L) { "rate-limit window must be positive" }
        require(maxTrackedKeys > 0) { "tracked-key capacity must be positive" }
    }

    fun tryAcquire(key: K): Boolean = synchronized(lock) {
        val now = clock()
        operations = (operations + 1) and Int.MAX_VALUE
        if (operations % CLEANUP_INTERVAL == 0 || windows.size >= maxTrackedKeys) {
            removeExpired(now)
        }

        val current = windows[key]
        if (current == null && windows.size >= maxTrackedKeys) {
            return@synchronized false
        }

        val next = if (current == null || now >= current.expiresAt) {
            Window(expiresAt = saturatedAdd(now, windowMillis), count = 1)
        } else {
            // 一旦被拒绝，保持一个小的饱和计数器，而不是允许整数溢出。
            val deniedSentinel = limit + 1
            current.copy(
                count = if (current.count >= deniedSentinel) deniedSentinel else current.count + 1,
            )
        }
        windows[key] = next
        next.count <= limit
    }

    internal fun trackedKeyCount(): Int = synchronized(lock) { windows.size }

    private fun removeExpired(now: Long) {
        val iterator = windows.entries.iterator()
        while (iterator.hasNext()) {
            if (now >= iterator.next().value.expiresAt) iterator.remove()
        }
    }

    private companion object {
        const val CLEANUP_INTERVAL = 64

        fun saturatedAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
