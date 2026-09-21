@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.shared.client.platformDataDir
import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.repository.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.*

internal class IosMediaLease(val file: PlatformFile, private val release: () -> Unit) : AutoCloseable {
    private val released = PlatformAtomicBoolean(false)
    override fun close() { if (released.compareAndSet(false, true)) release() }
}

/** One authenticated HTTP owner and bounded local cache per login session. */
internal class IosMediaResources(private val dataState: AppDataState) : AutoCloseable {
    private val closed = PlatformAtomicBoolean(false)
    private val job = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.Main + job)
    val repository = FileRepository(dataState.deploymentIdentity.httpBaseUrl, dataState.userSession.uid,
        dataState::httpCredentialsSnapshot, dataState::reportHttpAuthExpired)
    private val root = platformDataDir().resolve("media").resolve(dataState.deploymentIdentity.fingerprint)
        .resolve(dataState.datasetId).resolve(dataState.userSession.uid).also { check(it.mkdirs() || it.isDirectory) }
    val stagingDirectory = root.resolve("staging").also { check(it.mkdirs() || it.isDirectory) }
    private val downloadMutex = Mutex()
    private val leaseLock = PlatformLock()
    private val pins = mutableMapOf<String, Int>()
    private val displayedResources = mutableSetOf<() -> Unit>()

    /** Compose effects can outlive the frame that retires a session; stop native readers first. */
    fun retainDisplay(onRetirement: () -> Unit): AutoCloseable {
        val retained = synchronized(leaseLock) {
            if (closed.get()) false else { displayedResources += onRetirement; true }
        }
        if (!retained) onRetirement()
        return AutoCloseable { synchronized(leaseLock) { displayedResources -= onRetirement } }
    }

    fun ensureOpen() { check(canDeliverUiResult()) { "Media session closed" } }
    fun canDeliverUiResult(): Boolean = !closed.get() && dataState.acceptsRendering
    fun childScope(name: String): CoroutineScope = CoroutineScope(scope.coroutineContext + SupervisorJob(job) + CoroutineName(name))
    private fun target(attachment: Attachment): PlatformFile {
        val suffix = attachment.name.substringAfterLast('.', "").lowercase().takeIf {
            it.length in 1..12 && it.all { char -> char.isLetterOrDigit() }
        }?.let { ".$it" }.orEmpty()
        return root.resolve(platformSha256Hex("${attachment.path}\n${attachment.size}".encodeToByteArray()) + suffix)
    }
    suspend fun isCached(attachment: Attachment): Boolean = withContext(Dispatchers.IO) {
        target(attachment).let { it.isFile && it.length() == attachment.size }
    }
    suspend fun acquire(attachment: Attachment, onProgress: (Float) -> Unit = {}): IosMediaLease {
        var acquired: IosMediaLease? = null
        try {
            return withContext(Dispatchers.IO) {
            downloadMutex.withLock {
                ensureOpen()
                require(attachment.size in 0..CACHE_QUOTA) { "附件超出本地媒体缓存容量" }
                val file = target(attachment)
                if (!file.isFile || file.length() != attachment.size) {
                    evictFor(attachment.size)
                    val pending = root.resolve("${file.name}.${platformRandomUuid()}.part")
                    try {
                        val descriptor = open(pending.path, O_CREAT or O_EXCL or O_WRONLY, 0x180)
                        check(descriptor >= 0) { "无法创建附件缓存" }
                        try {
                            repository.downloadTo(attachment, DownloadSink { bytes, offset, length ->
                                writeFully(descriptor, bytes, offset, length)
                            }, onProgress).getOrThrow()
                            check(fsync(descriptor) == 0) { "无法持久化附件缓存" }
                        } finally { close(descriptor) }
                        currentCoroutineContext().ensureActive()
                        ensureOpen()
                        check(pending.length() == attachment.size) { "附件内容不完整" }
                        file.atomicReplaceWith(pending)
                    } finally { pending.delete() }
                }
                synchronized(leaseLock) { pins[file.path] = (pins[file.path] ?: 0) + 1 }
                file.setLastModified(platformCurrentTimeMillis())
                IosMediaLease(file) {
                    synchronized(leaseLock) {
                        val count = pins[file.path] ?: 0
                        if (count <= 1) pins.remove(file.path) else pins[file.path] = count - 1
                    }
                }.also { acquired = it }
            }
            }
        } catch (failure: Throwable) {
            // withContext may discard its result when cancellation wins the dispatch back to Main.
            acquired?.close()
            throw failure
        }
    }
    private fun evictFor(required: Long) {
        val files = root.listFiles().orEmpty().filter { it.isFile }
        var total = files.sumOf { it.length() }
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total + required <= CACHE_QUOTA) return@forEach
            val pinned = synchronized(leaseLock) { file.path in pins }
            // A wrong-sized destination is invalid cache too; keeping it could block its own repair.
            if (!pinned) {
                val bytes = file.length()
                if (file.delete()) total -= bytes
            }
        }
        check(total + required <= CACHE_QUOTA) { "媒体缓存正在使用，请关闭预览后重试" }
    }
    suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) { root.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() } }
    suspend fun clearUnleasedCache() = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            ensureOpen()
            root.listFiles().orEmpty().forEach { file ->
                val pinned = synchronized(leaseLock) { file.path in pins }
                if (!pinned && file.isFile) check(file.delete()) { "Cannot remove cached attachment" }
            }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val readers = synchronized(leaseLock) { displayedResources.toList().also { displayedResources.clear() } }
        var failure: Throwable? = null
        readers.forEach { closeReader ->
            try { closeReader() }
            catch (next: Throwable) { if (failure == null) failure = next else if (failure !== next) failure?.addSuppressed(next) }
        }
        job.cancel()
        try { repository.close() }
        catch (next: Throwable) { if (failure == null) failure = next else if (failure !== next) failure?.addSuppressed(next) }
        failure?.let { throw it }
    }
    companion object { const val CACHE_QUOTA = 512L * 1024 * 1024 }
}

private fun writeFully(descriptor: Int, bytes: ByteArray, offset: Int, length: Int) {
    bytes.usePinned { pinned ->
        var written = 0
        while (written < length) {
            val count = write(descriptor, pinned.addressOf(offset + written), (length - written).toULong()).toInt()
            check(count > 0) { "写入附件缓存失败" }
            written += count
        }
    }
}

internal class IosFileTransfer(private val resources: IosMediaResources) {
    suspend fun uploadWithMeta(file: PlatformFile, contentType: String, identity: AttachmentUploadIdentity,
        displayName: String, onProgress: (Float) -> Unit) = resources.repository.uploadWithMeta(
        file.asUploadSource(), displayName, contentType, identity, onProgress,
    ).getOrThrow()
}

/** Runs before session construction, when no picker or recorder can own a staging file. */
internal fun clearIosAbandonedStaging() {
    val media = platformDataDir().resolve("media")
    media.listFiles().orEmpty().filter { it.isDirectory }.forEach { deployment ->
        deployment.listFiles().orEmpty().filter { it.isDirectory }.forEach { dataset ->
            dataset.listFiles().orEmpty().filter { it.isDirectory }.forEach { account ->
                val staging = account.resolve("staging")
                check(!staging.exists() || staging.deleteRecursively()) { "Cannot clear abandoned media imports" }
            }
        }
    }
}
