package com.virjar.tk.shared.platform

/** Reentrant synchronous lock. Never hold it across a suspension. */
expect class PlatformLock() {
    fun lock()
    fun unlock()
}

inline fun <T> synchronized(lock: PlatformLock, block: () -> T): T {
    lock.lock()
    try { return block() } finally { lock.unlock() }
}
inline fun <T> PlatformLock.withLock(block: () -> T): T = synchronized(this, block)

class PlatformAtomicBoolean(initial: Boolean) {
    private val lock = PlatformLock()
    private var value = initial
    fun get(): Boolean = synchronized(lock) { value }
    fun set(next: Boolean) = synchronized(lock) { value = next }
    fun compareAndSet(expected: Boolean, next: Boolean): Boolean = synchronized(lock) {
        if (value != expected) false else { value = next; true }
    }
}
class PlatformAtomicLong(initial: Long) {
    private val lock = PlatformLock()
    private var value = initial
    fun get(): Long = synchronized(lock) { value }
    fun set(next: Long) = synchronized(lock) { value = next }
    fun incrementAndGet(): Long = synchronized(lock) { ++value }
    fun getAndIncrement(): Long = synchronized(lock) { value++ }
    fun updateAndGet(update: (Long) -> Long): Long = synchronized(lock) {
        update(value).also { value = it }
    }
    fun accumulateAndGet(next: Long, accumulator: (Long, Long) -> Long): Long = synchronized(lock) {
        accumulator(value, next).also { value = it }
    }
    fun compareAndSet(expected: Long, next: Long): Boolean = synchronized(lock) {
        if (value != expected) false else { value = next; true }
    }
}
expect fun platformCurrentTimeMillis(): Long
expect fun platformMonotonicNanos(): Long
expect fun platformCurrentThreadId(): Long
expect fun platformRandomUuid(): String
expect fun platformSecureRandomBytes(size: Int): ByteArray
expect fun platformSha256(bytes: ByteArray): ByteArray
fun platformSha256Hex(bytes: ByteArray): String = platformSha256(bytes).joinToString("") {
    (it.toInt() and 255).toString(16).padStart(2, '0')
}
expect fun platformLogTimestamp(): String
internal expect fun platformSystemProperty(name: String): String?
internal expect fun platformGzip(bytes: ByteArray): ByteArray
internal expect fun platformDrainJob(job: kotlinx.coroutines.Job)

fun platformCanonicalUuid(value: String): String {
    require(UUID_PATTERN.matches(value)) { "Invalid UUID" }
    return value.lowercase()
}
private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
fun platformBase64(bytes: ByteArray): String = kotlin.io.encoding.Base64.Default.encode(bytes)
fun platformBase64Decode(value: String): ByteArray = kotlin.io.encoding.Base64.Default.decode(value)
fun platformBase64Url(bytes: ByteArray): String = kotlin.io.encoding.Base64.UrlSafe.encode(bytes).trimEnd('=')
fun platformBase64UrlDecode(value: String): ByteArray = kotlin.io.encoding.Base64.UrlSafe.decode(value.padEnd((value.length + 3) / 4 * 4, '='))

/** Snapshot views keep callbacks out of the map lock; callers own mutation ordering. */
class PlatformConcurrentMap<K, V> {
    private val lock = PlatformLock()
    private val entries = mutableMapOf<K, V>()
    operator fun get(key: K): V? = synchronized(lock) { entries[key] }
    operator fun set(key: K, value: V) { synchronized(lock) { entries[key] = value } }
    fun containsKey(key: K): Boolean = synchronized(lock) { entries.containsKey(key) }
    fun remove(key: K): V? = synchronized(lock) { entries.remove(key) }
    fun clear() = synchronized(lock) { entries.clear() }
    val values: List<V> get() = synchronized(lock) { entries.values.toList() }
    val size: Int get() = synchronized(lock) { entries.size }
}
