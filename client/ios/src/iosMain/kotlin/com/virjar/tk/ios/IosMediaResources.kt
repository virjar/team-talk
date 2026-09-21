@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.media.MediaCacheBudget
import com.virjar.tk.app.media.MediaCacheEntry
import com.virjar.tk.app.media.MediaCacheTargetCoordinator
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.shared.client.platformDataDir
import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.repository.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
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
    private val budget = MediaCacheBudget(CACHE_QUOTA)
    private val writes = MediaCacheTargetCoordinator()
    private val leaseLock = budget.lock
    private var initialized = false
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
        synchronized(leaseLock) {
            target(attachment).let { it.isFile && !it.isSymbolicLink() && it.length() == attachment.size }
        }
    }
    suspend fun acquire(attachment: Attachment, onProgress: (Float) -> Unit = {}): IosMediaLease {
        var acquired: IosMediaLease? = null
        try {
            return withContext(Dispatchers.IO) {
                ensureOpen()
                require(attachment.size in 0..CACHE_QUOTA) { "附件超出本地媒体缓存容量" }
                initializeCache()
                val file = target(attachment)
                cachedLease(file, attachment.size)?.let { acquired = it; return@withContext it }
                writes.withTarget(file.path) {
                    cachedLease(file, attachment.size)?.let { acquired = it; return@withTarget it }
                    val reservation = synchronized(leaseLock) {
                        ensureOpen()
                        budget.reserve(attachment.size, file.path,
                            finalFiles().map { MediaCacheEntry(it.path, it, it.length(), it.lastModified()) }, PlatformFile::delete)
                    }
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
                        check(pending.length() == attachment.size) { "附件内容不完整" }
                        reservation.commit {
                            ensureOpen()
                            file.atomicReplaceWith(pending)
                            pin(file).also { acquired = it }
                        }
                    } finally {
                        pending.delete()
                        reservation.close()
                    }
                }
            }
        } catch (failure: Throwable) {
            // withContext may discard its result when cancellation wins the dispatch back to Main.
            acquired?.close()
            throw failure
        }
    }
    private fun cachedLease(file: PlatformFile, expectedBytes: Long): IosMediaLease? = synchronized(leaseLock) {
        ensureOpen()
        if (file.isFile && !file.isSymbolicLink() && file.length() == expectedBytes) return@synchronized pin(file)
        if (file.exists() || file.isSymbolicLink()) {
            check(!budget.isPinned(file.path) && file.delete()) { "媒体缓存正在使用，请关闭预览后重试" }
        }
        null
    }
    private fun pin(file: PlatformFile): IosMediaLease {
        file.setLastModified(platformCurrentTimeMillis())
        val pin = budget.pin(file.path)
        return IosMediaLease(file, pin::close)
    }
    private fun isPartial(file: PlatformFile): Boolean = PARTIAL_NAME.matches(file.name)
    private fun initializeCache() = synchronized(leaseLock) {
        if (initialized) return@synchronized
        root.listFiles().orEmpty().filter { !it.isSymbolicLink() && it.isFile && isPartial(it) }
            .forEach { check(it.delete()) { "无法清理未完成的附件下载" } }
        budget.trim(finalFiles().map { MediaCacheEntry(it.path, it, it.length(), it.lastModified()) }, PlatformFile::delete)
        initialized = true
    }
    private fun finalFiles(): List<PlatformFile> = root.listFiles().orEmpty()
        .filter { it.isFile && !it.isSymbolicLink() && !isPartial(it) }
    suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) {
        synchronized(leaseLock) { finalFiles().sumOf { it.length() } }
    }
    suspend fun clearUnleasedCache() = withContext(Dispatchers.IO) {
        synchronized(leaseLock) {
            ensureOpen()
            finalFiles().filterNot { budget.isPinned(it.path) }
                .forEach { check(it.delete()) { "Cannot remove cached attachment" } }
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
    companion object {
        const val CACHE_QUOTA = 512L * 1024 * 1024
        private val PARTIAL_NAME = Regex("[0-9a-f]{64}(?:\\.[^.]{1,12})?\\.[0-9a-f-]{36}\\.part")
    }
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
