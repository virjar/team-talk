package com.virjar.tk.desktop.media

import com.virjar.tk.shared.AppError
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.repository.FileOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal const val DEFAULT_DESKTOP_MEDIA_QUOTA_BYTES: Long = AttachmentPolicy.MAX_UPLOAD_BYTES
internal const val DEFAULT_DESKTOP_MEDIA_CACHE_MAX_ENTRIES: Int = 4096

private val DESKTOP_MEDIA_SCOPE_NAME = Regex("[0-9a-f]{64}")
private val DESKTOP_MEDIA_FINAL_FILE_NAME = Regex("[0-9a-f]{64}\\.[a-z0-9]{1,10}")
private const val DESKTOP_MEDIA_PART_PREFIX = ".teamtalk-part-"
private const val DESKTOP_MEDIA_PART_SUFFIX = ".part"

/** 一个设备数据根拥有一个聚合缓存预算，与已认证的 namespace 无关。 */
private class DesktopMediaRootCapacity(
    val root: File,
    val quotaBytes: Long,
    val maxEntries: Int,
) {
    val lock = Any()
    var reservedBytes: Long = 0L
    var reservedEntries: Int = 0
    val consumerPins = mutableMapOf<String, Int>()
    var initialized: Boolean = false

    fun scopeDirectories(): Sequence<File> {
        check(root.isPlainDesktopMediaDirectory()) { "Desktop media root must remain a plain directory" }
        return root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { scope ->
                scope.isPlainDesktopMediaDirectory() && DESKTOP_MEDIA_SCOPE_NAME.matches(scope.name)
            }
    }

    /** 只有由 DesktopMediaCache 的内容寻址目标命名产生的文件才可被回收。 */
    fun finalFiles(): List<File> = scopeDirectories()
        .flatMap { scope -> scope.listFiles().orEmpty().asSequence() }
        .filter { file ->
            file.isPlainDesktopMediaFile() &&
                DESKTOP_MEDIA_FINAL_FILE_NAME.matches(file.name) &&
                !isDesktopMediaPartialFile(file)
        }
        .toList()
}

/** 进程内协调器；Desktop 的数据根文件锁排除了另一个应用进程。 */
private object DesktopMediaRootCapacityRegistry {
    private val roots = mutableMapOf<String, DesktopMediaRootCapacity>()

    fun get(cacheDir: File, quotaBytes: Long, maxEntries: Int): DesktopMediaRootCapacity {
        require(DESKTOP_MEDIA_SCOPE_NAME.matches(cacheDir.name)) {
            "Desktop media cache scope must be a lowercase SHA-256"
        }
        val root = requireNotNull(cacheDir.parentFile) {
            "Desktop media cache scope has no root"
        }.absoluteFile.normalize()
        ensurePlainDesktopMediaDirectory(root, "无法创建媒体缓存根目录")
        ensurePlainDesktopMediaDirectory(cacheDir, "无法创建媒体缓存目录")
        return synchronized(roots) {
            roots.getOrPut(root.path) {
                DesktopMediaRootCapacity(root, quotaBytes, maxEntries)
            }.also { existing ->
                check(existing.quotaBytes == quotaBytes && existing.maxEntries == maxEntries) {
                    "同一媒体缓存根不能切换字节或文件数上限"
                }
            }
        }
    }
}

private fun isDesktopMediaPartialFile(file: File): Boolean =
    file.isPlainDesktopMediaFile() &&
        file.name.startsWith(DESKTOP_MEDIA_PART_PREFIX) &&
        file.name.endsWith(DESKTOP_MEDIA_PART_SUFFIX)

private fun File.desktopMediaCapacityKey(): String = absoluteFile.normalize().path

private fun File.existsNoFollow(): Boolean = Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS)

private fun File.isPlainDesktopMediaDirectory(): Boolean =
    Files.isDirectory(toPath(), LinkOption.NOFOLLOW_LINKS)

private fun File.isPlainDesktopMediaFile(): Boolean =
    Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)

private fun ensurePlainDesktopMediaDirectory(directory: File, failure: String) {
    if (!directory.existsNoFollow()) directory.mkdir()
    require(directory.isPlainDesktopMediaDirectory()) {
        "$failure（目录不能是符号链接）: $directory"
    }
}

internal class DesktopMediaDownloadSizeException(message: String) : IllegalStateException(message)

internal class DesktopMediaCacheQuotaException(
    val quotaBytes: Long,
    val maxEntries: Int = DEFAULT_DESKTOP_MEDIA_CACHE_MAX_ENTRIES,
) : IllegalStateException("媒体缓存无法在字节与文件数上限内预留空间")

internal fun validateDesktopMediaDownloadSize(
    expectedBytes: Long,
    quotaBytes: Long,
    maxEntries: Int = DEFAULT_DESKTOP_MEDIA_CACHE_MAX_ENTRIES,
) {
    require(quotaBytes > 0L) { "quotaBytes must be positive" }
    require(maxEntries > 0) { "maxEntries must be positive" }
    if (expectedBytes !in 0L..AttachmentPolicy.MAX_UPLOAD_BYTES) {
        throw DesktopMediaDownloadSizeException("附件大小超出安全上限")
    }
    if (expectedBytes > quotaBytes) throw DesktopMediaCacheQuotaException(quotaBytes, maxEntries)
}

internal fun validateDesktopMediaResponseLength(contentLength: Long, expectedBytes: Long) {
    if (contentLength < 0L) return
    if (contentLength > AttachmentPolicy.MAX_UPLOAD_BYTES || contentLength != expectedBytes) {
        throw DesktopMediaDownloadSizeException("下载响应大小与附件元数据不一致")
    }
}

/**
 * Desktop 数据根的统一媒体缓存访问器。
 *
 * 缓存根目录已经由 [DesktopSessionResources] 按 canonical TCP+HTTP deployment fingerprint、datasetId 和 uid 隔离；本类只接受
 * TeamTalk 附件引用，并再次通过 [FileOps.resolveUrl] 绑定当前服务器。缓存文件名
 * 只包含 SHA-256 和经过白名单过滤的扩展名，不信任远端或消息中的原始文件名。
 *
 * 不使用 SQLite：文件本身的 mtime 就是 LRU 访问时间。同一 `media_e2` 根下的所有
 * deployment/dataset/uid scope 共享一个容量所有者，身份目录仍隔离，但旧 scope 不会让设备总占用
 * 随登录身份无界增长。扫描只识别本类生成的直属最终文件；录音目录、临时文件和未知资产不参与回收。
 */
internal class DesktopMediaCache(
    private val serverBaseUrl: String,
    private val credentialGate: DesktopCredentialGate,
    private val diagnostics: DesktopSessionDiagnostics,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val downloader: DesktopMediaDownloader = HttpDesktopMediaDownloader(),
    private val quotaBytes: Long = DEFAULT_DESKTOP_MEDIA_QUOTA_BYTES,
    private val maxEntries: Int = DEFAULT_DESKTOP_MEDIA_CACHE_MAX_ENTRIES,
    private val onAuthExpired: (rejectedAccessToken: String) -> Unit = {},
) : Closeable {

    private data class DownloadFlight(
        val expectedBytes: Long,
        val deferred: Deferred<DownloadOutcome>,
        val progressListeners: CopyOnWriteArrayList<ProgressRegistration>,
    )

    private data class ProgressRegistration(
        val listener: (Float) -> Unit,
        val failure: CompletableDeferred<Throwable> = CompletableDeferred(),
    )

    /**
     * 失败作为数据传递，这样协程栈追踪恢复在跨越 [Deferred.await] 时
     * 不会复制原始的 callback/owner Throwable。真正的 Job 取消仍会取消 Deferred 本身，
     * 从而保留 await 的即时取消语义。
     */
    private sealed interface DownloadOutcome {
        data class Success(val file: File) : DownloadOutcome
        data class Failure(val error: Throwable) : DownloadOutcome
    }

    private val closed = AtomicBoolean(false)
    private val flights = mutableMapOf<String, DownloadFlight>()
    private val flightsLock = Any()
    private val callbackGate = Any()
    private val rootCapacity = DesktopMediaRootCapacityRegistry.get(cacheDir, quotaBytes, maxEntries)

    /** 只读生命周期探针；调用方不能保留或修改 flight 条目。 */
    internal val activeDownloadFlightCount: Int
        get() = synchronized(flightsLock) { flights.size }

    init {
        require(quotaBytes > 0) { "媒体缓存配额必须大于 0" }
        require(maxEntries > 0) { "媒体缓存文件数上限必须大于 0" }
        ensurePlainDesktopMediaDirectory(cacheDir, "无法创建媒体缓存目录")
        initializeRoot()
        evictToQuota()
    }

    /** 返回缓存文件并刷新 LRU；会话关闭、未命中或文件已丢失时返回 null。 */
    fun cachedFile(attachment: Attachment): File? = cachedFile(
        reference = attachment.path,
        suggestedFileName = attachment.name,
        expectedBytes = attachment.size,
    )

    internal fun cachedFile(
        reference: String,
        suggestedFileName: String? = null,
        expectedBytes: Long,
    ): File? {
        if (closed.get()) return null
        credentialGate.ensureOwner()
        validateDesktopMediaDownloadSize(expectedBytes, quotaBytes, maxEntries)
        val target = targetFile(reference, suggestedFileName)
        return synchronized(rootCapacity.lock) {
            if (!target.isPlainDesktopMediaFile() || target.length() != expectedBytes) {
                return@synchronized null
            }
            target.setLastModified(System.currentTimeMillis())
            target
        }
    }

    fun cachedLease(
        attachment: Attachment,
        forceRefresh: Boolean = false,
    ): DesktopMediaFileLease? {
        if (closed.get()) return null
        credentialGate.ensureOwner()
        validateDesktopMediaDownloadSize(attachment.size, quotaBytes, maxEntries)
        val target = targetFile(attachment.path, attachment.name)
        return synchronized(rootCapacity.lock) {
            val exists = target.existsNoFollow()
            val validFinal = target.isPlainDesktopMediaFile() && target.length() == attachment.size
            if (forceRefresh || exists && !validFinal) {
                if (isPinnedLocked(target) || !target.delete() && target.existsNoFollow()) {
                    throw DesktopMediaCacheQuotaException(quotaBytes, maxEntries)
                }
                return@synchronized null
            }
            if (!validFinal) return@synchronized null
            target.setLastModified(System.currentTimeMillis())
            pinLocked(target)
        }
    }

    suspend fun ensureDownloadedLease(
        attachment: Attachment,
        onProgress: (Float) -> Unit = {},
    ): DesktopMediaFileLease {
        ensureOpen()
        validateDesktopMediaDownloadSize(attachment.size, quotaBytes, maxEntries)
        val target = targetFile(attachment.path, attachment.name)
        // 在缓存命中或完成的下载被观测到之前，先保护目标路径。
        // 最终消费者 pin 在同一把根锁下获取，先于该交接 pin 释放，
        // 因此另一个 namespace 绝不可能在发布与播放持有之间把文件逐出。
        val handoffPin = synchronized(rootCapacity.lock) {
            if (
                target.existsNoFollow() &&
                (!target.isPlainDesktopMediaFile() || target.length() != attachment.size)
            ) {
                if (isPinnedLocked(target) || !target.delete() && target.existsNoFollow()) {
                    throw DesktopMediaCacheQuotaException(quotaBytes, maxEntries)
                }
            }
            pinLocked(target)
        }
        try {
            while (true) {
                val file = ensureDownloaded(attachment, onProgress)
                coroutineContext.ensureActive()
                val lease = synchronized(rootCapacity.lock) {
                    if (!file.isPlainDesktopMediaFile() || file.length() != attachment.size) {
                        null
                    } else {
                        pinLocked(file)
                    }
                }
                if (lease != null) return lease
            }
        } finally {
            handoffPin.close()
        }
    }

    /**
     * 下载或复用已有缓存。同一会话内相同最终缓存目标只有一个网络请求；所有等待者共享结果，
     * 并分别接收后续进度。进度监听器在缓存 IO flight 上调用；UI 消费者必须通过
     * [DesktopMediaProgressHandoff] 交回组合 owner。下载先进入唯一 `.part` 文件，成功后才原子替换最终文件。
     */
    suspend fun ensureDownloaded(
        attachment: Attachment,
        onProgress: (Float) -> Unit = {},
    ): File = ensureDownloaded(
        reference = attachment.path,
        suggestedFileName = attachment.name,
        expectedBytes = attachment.size,
        onProgress = onProgress,
    )

    internal suspend fun ensureDownloaded(
        reference: String,
        suggestedFileName: String? = null,
        expectedBytes: Long,
        onProgress: (Float) -> Unit = {},
    ): File {
        coroutineContext.ensureActive()
        ensureOpen()
        validateDesktopMediaDownloadSize(expectedBytes, quotaBytes, maxEntries)
        cachedFile(reference, suggestedFileName, expectedBytes)?.let { return it }

        val target = targetFile(reference, suggestedFileName)
        val key = target.name
        val registration = ProgressRegistration(onProgress)
        val flight = synchronized(callbackGate) {
            ensureOpen()
            synchronized(flightsLock) {
                val cachedTarget = synchronized(rootCapacity.lock) {
                    if (target.isPlainDesktopMediaFile() && target.length() == expectedBytes) {
                        target.setLastModified(System.currentTimeMillis())
                        target
                    } else {
                        null
                    }
                }
                if (cachedTarget != null) {
                    coroutineContext.ensureActive()
                    return cachedTarget
                }
                val selected = flights[key]?.also { existing ->
                    if (existing.expectedBytes != expectedBytes) {
                        throw DesktopMediaDownloadSizeException(
                            "同一媒体缓存目标收到冲突的附件大小",
                        )
                    }
                } ?: createDownloadFlight(
                    key = key,
                    reference = reference,
                    suggestedFileName = suggestedFileName,
                    expectedBytes = expectedBytes,
                ).also { created ->
                    flights[key] = created
                    // 已取消 scope 的惰性子任务可能在发布之前就已经完成。
                    if (created.deferred.isCompleted && flights[key] === created) flights.remove(key)
                }
                selected.progressListeners += registration
                selected
            }
        }
        // 惰性启动让第一个等待者能在传输进度派发之前完成注册；
        // 并发跟随者可能启动同一个 Deferred，start() 刻意保持幂等。
        flight.deferred.start()

        val outcome = try {
            awaitDesktopMediaFlight(flight.deferred, registration.failure)
        } finally {
            synchronized(callbackGate) {
                flight.progressListeners.remove(registration)
            }
        }
        if (flight.deferred.isCompleted) retireDownloadFlight(key, flight)
        return when (outcome) {
            is DownloadOutcome.Success -> outcome.file
            is DownloadOutcome.Failure -> throw outcome.error
        }
    }

    /** 测试与会话启动使用的显式配额整理。 */
    fun evictToQuota() = synchronized(rootCapacity.lock) {
        requireActiveDirectoryShape()
        val files = rootCapacity.finalFiles()
        var residentBytes = files.sumOf(File::length)
        var residentEntries = files.size
        val availableBytes = (quotaBytes - rootCapacity.reservedBytes).coerceAtLeast(0L)
        val availableEntries = maxEntries - rootCapacity.reservedEntries
        if (residentBytes <= availableBytes && residentEntries <= availableEntries) {
            return@synchronized
        }

        // 字节压力保持现有的 80% 滞后；条目压力只恢复到上限。
        val targetBytes = if (residentBytes > availableBytes) {
            minOf(availableBytes, quotaBytes * 8 / 10)
        } else {
            availableBytes
        }
        files.asSequence()
            .filterNot(::isPinnedLocked)
            .sortedWith(compareBy(File::lastModified).thenBy(File::getPath))
            .forEach { file ->
                if (residentBytes <= targetBytes && residentEntries <= availableEntries) return@forEach
                val length = file.length()
                if (file.delete()) {
                    residentBytes -= length
                    residentEntries -= 1
                }
            }
        if (residentBytes > availableBytes || residentEntries > availableEntries) {
            throw DesktopMediaCacheQuotaException(quotaBytes, maxEntries)
        }
    }

    override fun close() {
        synchronized(callbackGate) {
            closed.set(true)
        }
        // 阻塞的 URLConnection 读取无法被即时取消。会话拥有的 downloader 在这里断开所有
        // 已注册连接；.part 临时文件由正在回退的传输删除（若进程在回退中途被杀，
        // 则由下次启动清理）。
        downloader.close()
    }

    private suspend fun downloadToCache(
        reference: String,
        suggestedFileName: String?,
        expectedBytes: Long,
        onProgress: (Float) -> Unit,
    ): File {
        ensureOpen()
        coroutineContext.ensureActive()
        val target = targetFile(reference, suggestedFileName)
        cachedFile(reference, suggestedFileName, expectedBytes)?.let { return it }
        val reservation = reserveCapacity(target, expectedBytes)
        val partial = try {
            Files.createTempFile(
                cacheDir.toPath(),
                DESKTOP_MEDIA_PART_PREFIX,
                DESKTOP_MEDIA_PART_SUFFIX,
            ).toFile()
        } catch (error: Throwable) {
            reservation.close()
            throw error
        }

        val request = DesktopMediaDownloadRequest(
            resolvedUrl = FileOps.resolveUrl(serverBaseUrl, reference),
            authorizationToken = credentialGate.requireAccessToken(),
            expectedBytes = expectedBytes,
        )
        try {
            val operationContext = coroutineContext
            val downloadedBytes = downloader.download(request, partial, onProgress)
            operationContext.ensureActive()
            require(partial.isPlainDesktopMediaFile()) { "媒体下载没有生成临时文件" }
            if (downloadedBytes != expectedBytes || partial.length() != expectedBytes) {
                throw DesktopMediaDownloadSizeException("下载内容大小与附件元数据不一致")
            }
            synchronized(callbackGate) {
                ensureOpen()
                operationContext.ensureActive()
                reservation.commit { moveAtomically(partial, target) }
                require(target.isPlainDesktopMediaFile()) { "媒体缓存发布结果不是普通文件" }
                target.setLastModified(System.currentTimeMillis())
                isolateOrdinaryProgressFailure { onProgress(1f) }
                diagnostics.record(DesktopSessionDiagnosticEvent.MEDIA_CACHE_STORED)
            }
            return target
        } catch (error: Throwable) {
            var shouldReportAuthExpired = false
            var terminalFailure = if (error is AppError.AuthExpired) {
                credentialGate.authoritativeFailure(request.authorizationToken, error).also { authoritative ->
                    shouldReportAuthExpired = authoritative is AppError.AuthExpired
                }
            } else {
                error
            }
            try {
                Files.deleteIfExists(partial.toPath())
            } catch (cleanupFailure: Throwable) {
                terminalFailure = mergeDesktopMediaCacheFailures(terminalFailure, cleanupFailure)
            }
            if (shouldReportAuthExpired) onAuthExpired(request.authorizationToken)
            throw terminalFailure
        } finally {
            reservation.close()
        }
    }

    private fun reserveCapacity(target: File, expectedBytes: Long): DesktopMediaCapacityReservation =
        synchronized(rootCapacity.lock) {
            validateDesktopMediaDownloadSize(expectedBytes, quotaBytes, maxEntries)
            if (
                target.existsNoFollow() &&
                (!target.isPlainDesktopMediaFile() || target.length() != expectedBytes)
            ) {
                if (isPinnedLocked(target) || !target.delete() && target.existsNoFollow()) {
                    throw DesktopMediaCacheQuotaException(quotaBytes, maxEntries)
                }
            }
            val files = rootCapacity.finalFiles()
            var residentBytes = files.sumOf(File::length)
            var residentEntries = files.size
            val availableForResidentBytes = quotaBytes - rootCapacity.reservedBytes - expectedBytes
            val availableForResidentEntries = maxEntries - rootCapacity.reservedEntries - 1
            files.asSequence()
                .filterNot { it == target }
                .filterNot(::isPinnedLocked)
                .sortedWith(compareBy(File::lastModified).thenBy(File::getPath))
                .forEach { file ->
                    if (
                        residentBytes <= availableForResidentBytes &&
                        residentEntries <= availableForResidentEntries
                    ) {
                        return@forEach
                    }
                    val length = file.length()
                    if (file.delete()) {
                        residentBytes -= length
                        residentEntries -= 1
                    }
                }
            if (
                availableForResidentBytes < 0L ||
                availableForResidentEntries < 0 ||
                residentBytes > availableForResidentBytes ||
                residentEntries > availableForResidentEntries
            ) {
                throw DesktopMediaCacheQuotaException(quotaBytes, maxEntries)
            }
            rootCapacity.reservedBytes += expectedBytes
            rootCapacity.reservedEntries += 1
            DesktopMediaCapacityReservation(expectedBytes)
        }

    private inner class DesktopMediaCapacityReservation(
        private val bytes: Long,
    ) : Closeable {
        private val active = AtomicBoolean(true)

        fun commit(install: () -> Unit) = synchronized(rootCapacity.lock) {
            check(active.get()) { "媒体缓存容量预留已经释放" }
            try {
                install()
            } finally {
                releaseLocked()
            }
        }

        override fun close() = synchronized(rootCapacity.lock) { releaseLocked() }

        private fun releaseLocked() {
            if (!active.compareAndSet(true, false)) return
            check(rootCapacity.reservedBytes >= bytes && rootCapacity.reservedEntries > 0) {
                "媒体缓存预留记账损坏"
            }
            rootCapacity.reservedBytes -= bytes
            rootCapacity.reservedEntries -= 1
        }
    }

    private fun pinLocked(file: File): DesktopMediaFileLease {
        val key = file.desktopMediaCapacityKey()
        rootCapacity.consumerPins[key] = rootCapacity.consumerPins.getOrDefault(key, 0) + 1
        return DesktopMediaFileLease(file) {
            synchronized(rootCapacity.lock) {
                val current = rootCapacity.consumerPins[key] ?: return@synchronized
                if (current == 1) {
                    rootCapacity.consumerPins.remove(key)
                } else {
                    rootCapacity.consumerPins[key] = current - 1
                }
            }
        }
    }

    private fun isPinnedLocked(file: File): Boolean =
        rootCapacity.consumerPins.getOrDefault(file.desktopMediaCapacityKey(), 0) > 0

    private fun targetFile(reference: String, suggestedFileName: String?): File {
        requireActiveDirectoryShape()
        val extension = safeExtension(suggestedFileName ?: reference)
        return File(cacheDir, "${cacheKey(reference)}.$extension")
    }

    private fun requireActiveDirectoryShape() {
        check(rootCapacity.root.isPlainDesktopMediaDirectory()) {
            "Desktop media root must remain a plain directory"
        }
        check(cacheDir.isPlainDesktopMediaDirectory()) {
            "Desktop media cache scope must remain a plain directory"
        }
    }

    private fun cacheKey(reference: String): String =
        desktopSha256(FileOps.resolveUrl(serverBaseUrl, reference))

    private fun ensureOpen() {
        check(!closed.get()) { "Desktop 媒体缓存已经关闭" }
        credentialGate.ensureOwner()
    }

    /** close 返回前等待已进入的回调结束；close 返回后不再允许迟到进度触达 UI。 */
    private fun dispatchProgress(
        listeners: CopyOnWriteArrayList<ProgressRegistration>,
        progress: Float,
    ) = synchronized(callbackGate) {
        if (closed.get()) return@synchronized
        val stillOwned = try {
            credentialGate.ensureOwner()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DesktopSessionUnavailableException) {
            false
        }
        if (!stillOwned) return@synchronized
        for (registration in listeners) {
            if (closed.get()) break
            try {
                registration.listener(progress)
            } catch (cancelled: CancellationException) {
                failProgressRegistration(listeners, registration, cancelled)
            } catch (_: Exception) {
                // UI 进度是尽力而为的；该注册仍可接收后续值。
            } catch (fatal: Throwable) {
                failProgressRegistration(listeners, registration, fatal)
            }
        }
    }

    /** 调用方持有 [callbackGate]。 */
    private fun failProgressRegistration(
        listeners: CopyOnWriteArrayList<ProgressRegistration>,
        registration: ProgressRegistration,
        failure: Throwable,
    ) {
        listeners.remove(registration)
        registration.failure.complete(failure)
    }

    private fun createDownloadFlight(
        key: String,
        reference: String,
        suggestedFileName: String?,
        expectedBytes: Long,
    ): DownloadFlight {
        val listeners = CopyOnWriteArrayList<ProgressRegistration>()
        val deferred = scope.async<DownloadOutcome>(start = CoroutineStart.LAZY) {
            try {
                DownloadOutcome.Success(
                    downloadToCache(reference, suggestedFileName, expectedBytes) { progress ->
                        dispatchProgress(listeners, progress)
                    },
                )
            } catch (error: Throwable) {
                // 绝不把 flight Job 自身的取消变成成功值。
                coroutineContext.ensureActive()
                DownloadOutcome.Failure(error)
            }
        }
        return DownloadFlight(expectedBytes, deferred, listeners).also { created ->
            deferred.invokeOnCompletion { retireDownloadFlight(key, created) }
        }
    }

    private fun retireDownloadFlight(key: String, flight: DownloadFlight) {
        synchronized(flightsLock) {
            if (flights[key] === flight) flights.remove(key)
        }
    }

    private inline fun isolateOrdinaryProgressFailure(callback: () -> Unit) {
        try {
            callback()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // UI 进度是尽力而为的。取消与 VM 级致命缺陷有意向外传播。
        }
    }

    /** 在本进程完成根的第一次扫描之前，任何预留都不能持有 .part 临时文件。 */
    private fun initializeRoot() = synchronized(rootCapacity.lock) {
        if (rootCapacity.initialized) return@synchronized
        rootCapacity.scopeDirectories().forEach { directory ->
            directory.listFiles()
                .orEmpty()
                .asSequence()
                .filter(::isDesktopMediaPartialFile)
                .filterNot { partial -> Files.isSymbolicLink(partial.toPath()) }
                .forEach { partial ->
                    if (!partial.delete() && partial.exists()) {
                        throw DesktopMediaCacheQuotaException(quotaBytes, maxEntries)
                    }
                }
        }
        rootCapacity.initialized = true
    }

    private fun moveAtomically(partial: File, target: File) {
        try {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        fun safeExtension(name: String): String {
            val candidate = name.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
                .lowercase()
                .filter { char -> char in 'a'..'z' || char in '0'..'9' }
                .take(10)
            return candidate.ifBlank { "bin" }
        }
    }
}

private fun mergeDesktopMediaCacheFailures(primary: Throwable, additional: Throwable): Throwable {
    if (primary === additional) return primary
    val primaryFatal = primary is CancellationException || primary !is Exception
    val additionalFatal = additional is CancellationException || additional !is Exception
    return if (!primaryFatal && additionalFatal) {
        additional.addSuppressed(primary)
        additional
    } else {
        primary.addSuppressed(additional)
        primary
    }
}

internal fun desktopSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal class DesktopMediaFileLease internal constructor(
    val file: File,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
