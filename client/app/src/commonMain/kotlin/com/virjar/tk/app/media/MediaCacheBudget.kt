package com.virjar.tk.app.media

import com.virjar.tk.shared.platform.PlatformLock
import com.virjar.tk.shared.platform.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MediaCacheEntry<T>(val key: String, val file: T, val bytes: Long, val lastUsed: Long)

/** 共享的容量与 pin 记账。各平台适配器只枚举自己的安全最终文件。 */
class MediaCacheBudget(
    val quotaBytes: Long,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val quotaFailure: () -> Throwable = { IllegalStateException("媒体缓存无法在字节与文件数上限内预留空间") },
) {
    val lock = PlatformLock()
    private val pins = mutableMapOf<String, Int>()
    var reservedBytes: Long = 0; private set
    var reservedEntries: Int = 0; private set
    val pinnedFileCount: Int get() = synchronized(lock) { pins.size }
    val isIdle: Boolean get() = synchronized(lock) { reservedEntries == 0 && pins.isEmpty() }

    init { require(quotaBytes > 0 && maxEntries > 0) }

    fun isPinned(key: String): Boolean = synchronized(lock) { key in pins }

    /** 与校验/安装文件一并在 [lock] 下获取，封住被逐出的间隙。 */
    fun pin(key: String): AutoCloseable = synchronized(lock) {
        pins[key] = (pins[key] ?: 0) + 1
        var active = true
        AutoCloseable {
            synchronized(lock) {
                if (active) {
                    active = false
                    val count = checkNotNull(pins[key])
                    if (count == 1) pins.remove(key) else pins[key] = count - 1
                }
            }
        }
    }

    fun <T> reserve(bytes: Long, targetKey: String, files: List<MediaCacheEntry<T>>, delete: (T) -> Boolean): Reservation =
        synchronized(lock) {
            require(bytes >= 0)
            evict(files, quotaBytes - reservedBytes - bytes, maxEntries - reservedEntries - 1, targetKey, delete)
            reservedBytes += bytes
            reservedEntries++
            Reservation(bytes)
        }

    /** 可选的字节滞回保留 Desktop 既有的 80% 低水位线。 */
    fun <T> trim(files: List<MediaCacheEntry<T>>, delete: (T) -> Boolean, byteHysteresis: Double = 1.0) = synchronized(lock) {
        require(byteHysteresis in 0.0..1.0)
        val available = quotaBytes - reservedBytes
        val target = if (files.sumOf { it.bytes } > available) minOf(available, (quotaBytes * byteHysteresis).toLong()) else available
        evict(files, target, maxEntries - reservedEntries, null, delete, requiredBytes = available)
    }

    private fun <T> evict(files: List<MediaCacheEntry<T>>, targetBytes: Long, targetEntries: Int,
        excludedKey: String?, delete: (T) -> Boolean, requiredBytes: Long = targetBytes) {
        var bytes = files.sumOf { it.bytes }
        var entries = files.size
        for (entry in files.sortedWith(compareBy<MediaCacheEntry<T>> { it.lastUsed }.thenBy { it.key })) {
            if (bytes <= targetBytes && entries <= targetEntries) break
            if (entry.key == excludedKey || entry.key in pins) continue
            if (delete(entry.file)) { bytes -= entry.bytes; entries-- }
        }
        if (requiredBytes < 0 || targetEntries < 0 || bytes > requiredBytes || entries > targetEntries) throw quotaFailure()
    }

    inner class Reservation internal constructor(private val bytes: Long) : AutoCloseable {
        private var active = true

        /** 安装与可选的 pin 同释放容量预留原子地进行。 */
        fun <T> commit(install: () -> T): T = synchronized(lock) {
            check(active) { "媒体缓存容量预留已经释放" }
            try { install() } finally { close() }
        }

        override fun close() = synchronized(lock) {
            if (active) {
                active = false
                check(reservedBytes >= bytes && reservedEntries > 0) { "媒体缓存预留记账损坏" }
                reservedBytes -= bytes
                reservedEntries--
            }
        }
    }

    companion object { const val DEFAULT_MAX_ENTRIES = 4096 }
}

/** 只有同一附件会等待其写入者；条目随最后一个等待者一起消失。 */
class MediaCacheTargetCoordinator {
    private class Target(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val lock = PlatformLock()
    private val targets = mutableMapOf<String, Target>()

    suspend fun <T> withTarget(key: String, action: suspend () -> T): T {
        val target = synchronized(lock) { targets.getOrPut(key) { Target() }.also { it.users++ } }
        try { return target.mutex.withLock { action() } }
        finally {
            synchronized(lock) { if (--target.users == 0) targets.remove(key) }
        }
    }
}
