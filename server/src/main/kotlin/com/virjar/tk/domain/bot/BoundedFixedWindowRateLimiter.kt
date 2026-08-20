package com.virjar.tk.domain.bot

/**
 * Process-local fixed-window limiter with a strict key bound.
 *
 * The server currently runs as one instance, so this limiter is intentionally local. Expired
 * windows are removed opportunistically; when every tracked key is still active, an unseen key is
 * rejected instead of growing memory without limit. A future multi-instance deployment must move
 * this policy behind a shared/transactional rate-limit port.
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
            // Once denied, keep a small saturated counter instead of allowing integer overflow.
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
