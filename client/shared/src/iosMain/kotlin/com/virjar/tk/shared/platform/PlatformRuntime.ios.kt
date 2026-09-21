@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import platform.Foundation.*
import platform.Security.*
import platform.CoreCrypto.*
import platform.posix.clock_gettime_nsec_np
import platform.posix.CLOCK_MONOTONIC_RAW

actual class PlatformLock actual constructor() {
    private val delegate = NSRecursiveLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
actual fun platformCurrentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
actual fun platformMonotonicNanos(): Long = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()).toLong()
actual fun platformCurrentThreadId(): Long = memScoped {
    val id = alloc<ULongVar>()
    check(platform.posix.pthread_threadid_np(null, id.ptr) == 0)
    id.value.toLong()
}
actual fun platformRandomUuid(): String = NSUUID().UUIDString.lowercase()
actual fun platformSecureRandomBytes(size: Int): ByteArray {
    require(size >= 0)
    return ByteArray(size).also { bytes ->
        if (bytes.isNotEmpty()) bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) == errSecSuccess) }
    }
}
actual fun platformSha256(bytes: ByteArray): ByteArray {
    val result = ByteArray(CC_SHA256_DIGEST_LENGTH)
    result.usePinned { out ->
        if (bytes.isEmpty()) CC_SHA256(null, 0u, out.addressOf(0).reinterpret())
        else bytes.usePinned { input -> CC_SHA256(input.addressOf(0), bytes.size.toUInt(), out.addressOf(0).reinterpret()) }
    }
    return result
}
actual fun platformLogTimestamp(): String = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"; locale = NSLocale("en_US_POSIX") }.stringFromDate(NSDate())
internal actual fun platformSystemProperty(name: String): String? = null
internal actual fun platformDrainJob(job: Job) {
    job.cancel()
    runBlocking { job.join() }
}

/** RFC 1952 gzip with bounded, uncompressed DEFLATE blocks; no native compression lifetime. */
internal actual fun platformGzip(bytes: ByteArray): ByteArray {
    val blocks = maxOf(1, (bytes.size + 65534) / 65535)
    val output = ByteArray(10 + bytes.size + blocks * 5 + 8)
    byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 3).copyInto(output)
    var source = 0
    var dest = 10
    repeat(blocks) { block ->
        val count = minOf(65535, bytes.size - source)
        output[dest++] = if (block == blocks - 1) 1 else 0
        output[dest++] = count.toByte(); output[dest++] = (count ushr 8).toByte()
        output[dest++] = count.inv().toByte(); output[dest++] = (count.inv() ushr 8).toByte()
        bytes.copyInto(output, dest, source, source + count); dest += count; source += count
    }
    var crc = -1
    bytes.forEach { byte ->
        crc = crc xor (byte.toInt() and 255)
        repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
    }
    for (value in intArrayOf(crc.inv(), bytes.size)) repeat(4) { shift -> output[dest++] = (value ushr (shift * 8)).toByte() }
    return output
}
