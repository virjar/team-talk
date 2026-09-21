package com.virjar.tk.shared.platform

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

actual class PlatformLock actual constructor() {
    private val delegate = ReentrantLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()
actual fun platformMonotonicNanos(): Long = System.nanoTime()
actual fun platformCurrentThreadId(): Long = Thread.currentThread().id
actual fun platformRandomUuid(): String = UUID.randomUUID().toString()
actual fun platformSecureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
actual fun platformSha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
actual fun platformLogTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
internal actual fun platformSystemProperty(name: String): String? = System.getProperty(name)
internal actual fun platformGzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
    GZIPOutputStream(output).use { it.write(bytes) }
    output.toByteArray()
}
internal actual fun platformDrainJob(job: Job) {
    job.cancel()
    var interruption: InterruptedException? = null
    while (!job.isCompleted) {
        try { runBlocking { job.join() } } catch (failure: InterruptedException) {
            if (interruption == null) interruption = failure else interruption.addSuppressed(failure)
        }
    }
    interruption?.let { Thread.currentThread().interrupt(); throw it }
}
