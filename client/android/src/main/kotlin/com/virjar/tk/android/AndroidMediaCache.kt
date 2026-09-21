package com.virjar.tk.android

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.app.media.MediaCacheBudget
import com.virjar.tk.app.media.MediaCacheEntry
import com.virjar.tk.app.media.MediaCacheTargetCoordinator
import com.virjar.tk.shared.platform.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** 一个 Android 缓存根目录下的所有已下载媒体共享这个有界预算。 */
internal const val DEFAULT_ANDROID_MEDIA_CACHE_QUOTA_BYTES: Long = AttachmentPolicy.MAX_UPLOAD_BYTES
internal const val DEFAULT_ANDROID_MEDIA_CACHE_MAX_ENTRIES: Int = MediaCacheBudget.DEFAULT_MAX_ENTRIES
private const val ANDROID_MEDIA_CACHE_PART_PREFIX = ".teamtalk-part-"
private const val ANDROID_MEDIA_CACHE_PART_SUFFIX = ".part"
private val ANDROID_MEDIA_CACHE_SCOPE_NAME = Regex("[0-9a-f]{32}")
private val ANDROID_MEDIA_DOWNLOAD_FILE_NAME = Regex("[0-9a-f]{64}\\.[^/\\\\\\u0000-\\u001f]{0,5}")
private val ANDROID_MEDIA_ATTACHMENT_FILE_NAME =
    Regex("[0-9a-f]{32}-[^/\\\\:*?\"<>|\\u0000-\\u001f]{1,120}")

internal class MediaDownloadSizeException(message: String) : IllegalStateException(message)

internal class MediaCacheQuotaException(
    val quotaBytes: Long,
    val maxEntries: Int = DEFAULT_ANDROID_MEDIA_CACHE_MAX_ENTRIES,
) : IllegalStateException("媒体缓存无法在字节与文件数上限内预留空间")

internal fun validateMediaDownloadSize(
    expectedBytes: Long,
    cacheQuotaBytes: Long,
    cacheMaxEntries: Int = DEFAULT_ANDROID_MEDIA_CACHE_MAX_ENTRIES,
) {
    require(cacheQuotaBytes > 0L) { "cacheQuotaBytes must be positive" }
    require(cacheMaxEntries > 0) { "cacheMaxEntries must be positive" }
    if (expectedBytes !in 0L..AttachmentPolicy.MAX_UPLOAD_BYTES) {
        throw MediaDownloadSizeException("附件大小超出安全上限")
    }
    if (expectedBytes > cacheQuotaBytes) {
        throw MediaCacheQuotaException(cacheQuotaBytes, cacheMaxEntries)
    }
}

/** Content-Length 是可选的预检；流式字节计数始终是权威依据。 */
internal fun validateMediaResponseLength(contentLength: Long, expectedBytes: Long) {
    if (contentLength < 0L) return
    if (contentLength > AttachmentPolicy.MAX_UPLOAD_BYTES || contentLength != expectedBytes) {
        throw MediaDownloadSizeException("下载响应大小与附件元数据不一致")
    }
}

/** 所有 Android 媒体临时文件与下载缓存都收敛到会话隔离目录。 */
internal fun mediaCacheDirectory(
    cacheRoot: File,
    cacheNamespace: String,
    category: String,
): File {
    require(category.matches(Regex("[a-z0-9-]+"))) { "invalid media cache category" }
    val opaqueScope = sha256Hex(cacheNamespace).take(32)
    if (category == "attachments" || category == "captured") {
        // FileProvider XML 无法表达 teamtalk-media/<动态 scope>/<类别> 通配路径。
        // 把可分享文件集中到固定的窄前缀，避免授权整个 teamtalk-media 或 cacheDir。
        return File(cacheRoot, "teamtalk-media/$category/$opaqueScope")
    }
    return File(cacheRoot, "teamtalk-media/$opaqueScope/$category")
}

/** 只创建参与全局媒体 LRU 的那两种缓存目录形状。 */
internal fun ensureManagedMediaCacheDirectory(
    cacheRoot: File,
    cacheNamespace: String,
    category: String,
): File {
    require(category == "downloads" || category == "attachments") {
        "unsupported managed media cache category"
    }
    check(cacheRoot.isPlainDirectory()) { "Android cache root must be a plain directory" }
    val mediaRoot = ensurePlainChildDirectory(cacheRoot, "teamtalk-media")
    val opaqueScope = sha256Hex(cacheNamespace).take(32)
    return if (category == "attachments") {
        val attachments = ensurePlainChildDirectory(mediaRoot, "attachments")
        ensurePlainChildDirectory(attachments, opaqueScope)
    } else {
        val scope = ensurePlainChildDirectory(mediaRoot, opaqueScope)
        ensurePlainChildDirectory(scope, "downloads")
    }
}

internal const val FILE_PROVIDER_ATTACHMENTS_PATH = "teamtalk-media/attachments/"

/**
 * 同一缓存目标只允许一个写入者。共享协调器在最后一个等待者退出时移除锁对象。
 * 后到的缩略图/画廊请求在获锁后会直接命中首个请求已经落盘的文件。
 */
private val mediaCacheWrites = MediaCacheTargetCoordinator()

/**
 * 一个 Android 缓存根目录的进程级容量所有者。
 *
 * 每次传输都会在 HTTP 开始之前预留其声明的最终大小和一个预期的文件条目。
 * 所有部署/数据集/账户命名空间下的最终文件加上所有活跃的预留，
 * 始终保持在 [DEFAULT_ANDROID_MEDIA_CACHE_QUOTA_BYTES] 和
 * [DEFAULT_ANDROID_MEDIA_CACHE_MAX_ENTRIES] 之内，
 * 即使互不相关的图片、语音、视频和文件附件并发下载也是如此。
 */
internal object AndroidMediaCacheCapacityRegistry {
    internal data class Key(val cacheRootPath: String)

    private class State(
        val quotaBytes: Long,
        val maxEntries: Int,
    ) {
        val budget = MediaCacheBudget(quotaBytes, maxEntries) { MediaCacheQuotaException(quotaBytes, maxEntries) }
        val lock = budget.lock
        var initialized: Boolean = false
    }

    private val states = mutableMapOf<Key, State>()

    fun reserve(
        cacheRoot: File,
        expectedBytes: Long,
        target: File,
        quotaBytes: Long = DEFAULT_ANDROID_MEDIA_CACHE_QUOTA_BYTES,
        maxEntries: Int = DEFAULT_ANDROID_MEDIA_CACHE_MAX_ENTRIES,
    ): AndroidMediaCacheReservation {
        validateMediaDownloadSize(expectedBytes, quotaBytes, maxEntries)
        requireManagedMediaCacheTarget(cacheRoot, target)
        val key = key(cacheRoot)
        while (true) {
            val state = synchronized(this) {
                states.getOrPut(key) { State(quotaBytes, maxEntries) }
            }
            val reservation = state.lock.withLock {
                // 测试清理可能在映射查找与获得此锁之间退役一个空闲状态。
                if (synchronized(this) { states[key] !== state }) return@withLock null
                check(state.quotaBytes == quotaBytes && state.maxEntries == maxEntries) {
                    "同一 Android 缓存根目录不能切换字节或文件数上限"
                }
                if (!state.initialized) {
                    removeAbandonedPartials(cacheRoot, quotaBytes, maxEntries)
                    state.initialized = true
                }

                val reserved = state.budget.reserve(expectedBytes, target.normalizedPath(),
                    finalCacheFiles(cacheRoot).map { it.cacheEntry() }, File::delete)
                AndroidMediaCacheReservation(reserved) { installed ->
                    require(installed.isPlainFile() && installed.length() == expectedBytes) {
                        "只能租用完整发布的媒体缓存"
                    }
                    acquireLeaseLocked(state, installed)
                }
            }
            if (reservation != null) return reservation
        }
    }

    /**
     * 原子地校验并固定一个已存在的最终文件。并发的预留无法在缓存命中检查与租约获取之间
     * 逐出该文件，因为两者都使用 [State.lock]。[forceRefresh] 会移除未固定的最终文件，
     * 让播放/解析失败可以从权威下载重试，而不是永远重新打开同一份损坏的字节。
     */
    fun cachedLease(
        cacheRoot: File,
        file: File,
        expectedBytes: Long,
        forceRefresh: Boolean = false,
        quotaBytes: Long = DEFAULT_ANDROID_MEDIA_CACHE_QUOTA_BYTES,
        maxEntries: Int = DEFAULT_ANDROID_MEDIA_CACHE_MAX_ENTRIES,
    ): AndroidMediaCacheFileLease? {
        validateMediaDownloadSize(expectedBytes, quotaBytes, maxEntries)
        requireManagedMediaCacheTarget(cacheRoot, file)
        val key = key(cacheRoot)
        while (true) {
            val state = synchronized(this) {
                states.getOrPut(key) { State(quotaBytes, maxEntries) }
            }
            val path = file.normalizedPath()
            var staleState = false
            val lease = state.lock.withLock {
                if (synchronized(this) { states[key] !== state }) {
                    staleState = true
                    return@withLock null
                }
                check(state.quotaBytes == quotaBytes && state.maxEntries == maxEntries) {
                    "同一 Android 缓存根目录不能切换字节或文件数上限"
                }
                val exists = file.existsNoFollow()
                val validFinal = file.isPlainFile() && file.length() == expectedBytes
                if (forceRefresh || exists && !validFinal) {
                    if (state.budget.isPinned(path) || !file.delete() && file.existsNoFollow()) {
                        throw MediaCacheQuotaException(quotaBytes, maxEntries)
                    }
                    return@withLock null
                }
                if (!validFinal) return@withLock null
                file.setLastModified(System.currentTimeMillis())
                acquireLeaseLocked(state, file)
            }
            if (staleState) continue
            return lease
        }
    }

    fun forget(cacheRoot: File) {
        val key = key(cacheRoot)
        while (true) {
            val state = synchronized(this) { states[key] } ?: return
            val retired = state.lock.withLock {
                synchronized(this) {
                    if (states[key] !== state) {
                        false
                    } else if (
                        state.budget.isIdle
                    ) {
                        states.remove(key, state)
                        true
                    } else {
                        return
                    }
                }
            }
            if (retired) return
        }
    }

    internal fun reservedBytesForTest(cacheRoot: File): Long {
        val state = synchronized(this) { states[key(cacheRoot)] } ?: return 0L
        return state.lock.withLock { state.budget.reservedBytes }
    }

    internal fun reservedEntriesForTest(cacheRoot: File): Int {
        val state = synchronized(this) { states[key(cacheRoot)] } ?: return 0
        return state.lock.withLock { state.budget.reservedEntries }
    }

    internal fun pinnedFilesForTest(cacheRoot: File): Int {
        val state = synchronized(this) { states[key(cacheRoot)] } ?: return 0
        return state.lock.withLock { state.budget.pinnedFileCount }
    }

    internal fun stateCountForTest(): Int = synchronized(this) { states.size }

    private fun key(cacheRoot: File) = Key(
        cacheRootPath = cacheRoot.absoluteFile.normalize().path,
    )

    private fun finalCacheFiles(cacheRoot: File): List<File> = buildList {
        cacheDirectories(cacheRoot).forEach { cacheDirectory ->
            cacheDirectory.directory.listFiles().orEmpty()
                .asSequence()
                .filter(File::isPlainFile)
                .filter { cacheDirectory.finalFileName.matches(it.name) }
                .forEach(::add)
        }
    }

    /** 在根状态完成首次使用清理之前，任何预留都不能拥有一个临时分片文件。 */
    private fun removeAbandonedPartials(
        cacheRoot: File,
        quotaBytes: Long,
        maxEntries: Int,
    ) {
        cacheDirectories(cacheRoot).forEach { cacheDirectory ->
            cacheDirectory.directory.listFiles().orEmpty()
                .asSequence()
                .filter(File::isPlainFile)
                .filter(File::isAndroidMediaCachePartial)
                .forEach { partial ->
                    if (!partial.delete() && partial.exists()) {
                        throw MediaCacheQuotaException(quotaBytes, maxEntries)
                    }
                }
        }
    }

    private fun cacheDirectories(cacheRoot: File): Sequence<CacheDirectory> = sequence {
        val mediaRoot = File(cacheRoot, "teamtalk-media")
        mediaRoot.plainChildDirectories()
            .filter { ANDROID_MEDIA_CACHE_SCOPE_NAME.matches(it.name) }
            .map { scope -> File(scope, "downloads") }
            .filter(File::isPlainDirectory)
            .forEach { downloads ->
                yield(CacheDirectory(downloads, ANDROID_MEDIA_DOWNLOAD_FILE_NAME))
            }

        File(mediaRoot, "attachments").plainChildDirectories()
            .filter { ANDROID_MEDIA_CACHE_SCOPE_NAME.matches(it.name) }
            .forEach { attachments ->
                yield(CacheDirectory(attachments, ANDROID_MEDIA_ATTACHMENT_FILE_NAME))
            }
    }

    /** 调用方持有 [State.lock]。 */
    private fun acquireLeaseLocked(state: State, file: File): AndroidMediaCacheFileLease {
        require(file.isPlainFile()) { "只能租用普通媒体缓存文件" }
        return AndroidMediaCacheFileLease(file, state.budget.pin(file.normalizedPath()))
    }

    private data class CacheDirectory(
        val directory: File,
        val finalFileName: Regex,
    )
}

private fun File.normalizedPath(): String = absoluteFile.normalize().path
private fun File.cacheEntry() = MediaCacheEntry(normalizedPath(), this, length(), lastModified())

private fun File.existsNoFollow(): Boolean = Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS)

private fun File.isPlainDirectory(): Boolean =
    Files.isDirectory(toPath(), LinkOption.NOFOLLOW_LINKS)

private fun File.isPlainFile(): Boolean =
    Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)

private fun ensurePlainChildDirectory(parent: File, name: String): File {
    check(parent.isPlainDirectory()) { "媒体缓存父目录不是普通目录" }
    val child = File(parent, name)
    if (!child.existsNoFollow()) child.mkdir()
    check(child.isPlainDirectory()) { "媒体缓存目录不能是符号链接" }
    return child
}

private fun requireManagedMediaCacheTarget(cacheRoot: File, target: File) {
    check(cacheRoot.isPlainDirectory()) { "Android cache root must be a plain directory" }
    val mediaRoot = File(cacheRoot, "teamtalk-media")
    check(mediaRoot.isPlainDirectory()) { "Android media root must be a plain directory" }
    val mediaRootPath = mediaRoot.absoluteFile.normalize().toPath()
    val targetPath = target.absoluteFile.normalize().toPath()
    require(targetPath.startsWith(mediaRootPath)) { "媒体缓存目标越出根目录" }
    val relative = mediaRootPath.relativize(targetPath)
    val segments = (0 until relative.nameCount).map { relative.getName(it).toString() }
    val validDownload = segments.size == 3 &&
        ANDROID_MEDIA_CACHE_SCOPE_NAME.matches(segments[0]) &&
        segments[1] == "downloads" &&
        ANDROID_MEDIA_DOWNLOAD_FILE_NAME.matches(segments[2])
    val validAttachment = segments.size == 3 &&
        segments[0] == "attachments" &&
        ANDROID_MEDIA_CACHE_SCOPE_NAME.matches(segments[1]) &&
        ANDROID_MEDIA_ATTACHMENT_FILE_NAME.matches(segments[2])
    require(validDownload || validAttachment) { "媒体缓存目标形状无效" }

    var directory = mediaRoot
    segments.dropLast(1).forEach { segment ->
        directory = File(directory, segment)
        check(directory.isPlainDirectory()) { "媒体缓存目录不能是符号链接" }
    }
}

private fun File.plainChildDirectories(): Sequence<File> =
    if (isPlainDirectory()) {
        listFiles().orEmpty().asSequence().filter(File::isPlainDirectory)
    } else {
        emptySequence()
    }

private fun File.isAndroidMediaCachePartial(): Boolean =
    name.startsWith(ANDROID_MEDIA_CACHE_PART_PREFIX) && name.endsWith(ANDROID_MEDIA_CACHE_PART_SUFFIX)

internal class AndroidMediaCacheReservation internal constructor(
    private val reservation: MediaCacheBudget.Reservation,
    private val acquireLeaseLocked: (File) -> AndroidMediaCacheFileLease,
) : AutoCloseable {
    internal fun commit(install: () -> Unit) = reservation.commit(install)
    internal fun commitAsLease(file: File, install: () -> Unit): AndroidMediaCacheFileLease = reservation.commit {
        install()
        acquireLeaseLocked(file)
    }
    override fun close() = reservation.close()
}

internal class AndroidMediaCacheFileLease internal constructor(
    val file: File,
    private val pin: AutoCloseable,
) : AutoCloseable {
    override fun close() = pin.close()
}

/**
 * 把可关闭资源的创建转移到 [context]，即使取消赢得了回交给调用方的调度器交接窗口
 * 也不会丢失该资源。`withContext` 可能在该窗口中丢弃已完成的结果，
 * 因此生产者在返回之前会把所有权发布给此辅助函数。
 */
internal suspend fun <T : AutoCloseable> withCloseableContext(
    context: CoroutineContext,
    create: suspend () -> T,
): T {
    val awaitingDelivery = AtomicReference<T?>(null)
    return try {
        val delivered = withContext(context) {
            create().also(awaitingDelivery::set)
        }
        check(awaitingDelivery.compareAndSet(delivered, null)) { "资源交接记账损坏" }
        delivered
    } catch (failure: Throwable) {
        awaitingDelivery.getAndSet(null)?.let { resource ->
            try {
                resource.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
        }
        throw failure
    }
}

/**
 * 物化一个最终缓存条目并以已固定状态返回。缓存命中、强制刷新失效、原子安装以及
 * 预留到租约的转换共享同一把容量锁，因此调用方绝不会观察到
 * 一个可能在原生消费者打开之前就被 LRU 删除的裸最终文件。
 */
internal suspend fun materializePinnedMediaCacheFile(
    target: File,
    expectedBytes: Long,
    acquireCachedLease: () -> AndroidMediaCacheFileLease?,
    reserveCapacity: () -> AndroidMediaCacheReservation,
    install: (partial: File, target: File) -> Unit = { partial, final ->
        try {
            Files.move(
                partial.toPath(),
                final.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    },
    writePartial: suspend (File) -> Unit,
): AndroidMediaCacheFileLease = mediaCacheWrites.withTarget(target.normalizedPath()) {
    currentCoroutineContext().ensureActive()
    acquireCachedLease()?.let { return@withTarget it }

    target.parentFile?.mkdirs()
    val reservation = reserveCapacity()
    val partial = try {
        File.createTempFile(
            ANDROID_MEDIA_CACHE_PART_PREFIX,
            ANDROID_MEDIA_CACHE_PART_SUFFIX,
            target.parentFile,
        )
    } catch (error: Throwable) {
        reservation.close()
        throw error
    }
    try {
        writePartial(partial)
        currentCoroutineContext().ensureActive()
        check(partial.isFile) { "下载没有生成缓存文件" }
        if (partial.length() != expectedBytes) {
            throw MediaDownloadSizeException("下载内容大小与附件元数据不一致")
        }
        val lease = reservation.commitAsLease(target) { install(partial, target) }
        target.setLastModified(System.currentTimeMillis())
        lease
    } catch (error: Throwable) {
        partial.delete()
        throw error
    } finally {
        reservation.close()
    }
}

/**
 * 以唯一临时文件写入，再在同一目录内原子改名为最终缓存。
 * 最终文件永远不会暴露半成品，失败或取消也只会清理本次的临时文件。
 */
internal suspend fun materializeMediaCacheFile(
    target: File,
    expectedBytes: Long? = null,
    reserveCapacity: (() -> AndroidMediaCacheReservation)? = null,
    install: (partial: File, target: File) -> Unit = { partial, final ->
        try {
            Files.move(
                partial.toPath(),
                final.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    },
    writePartial: suspend (File) -> Unit,
): File = mediaCacheWrites.withTarget(target.normalizedPath()) {
    currentCoroutineContext().ensureActive()
    if (target.isFile && (expectedBytes == null || target.length() == expectedBytes)) {
        target.setLastModified(System.currentTimeMillis())
        return@withTarget target
    }

    target.parentFile?.mkdirs()
    if (target.exists() && !target.delete()) {
        error("无法替换损坏的媒体缓存")
    }
    val reservation = reserveCapacity?.invoke()
    val partial = try {
        File.createTempFile(
            ANDROID_MEDIA_CACHE_PART_PREFIX,
            ANDROID_MEDIA_CACHE_PART_SUFFIX,
            target.parentFile,
        )
    } catch (error: Throwable) {
        reservation?.close()
        throw error
    }
    try {
        writePartial(partial)
        currentCoroutineContext().ensureActive()
        check(partial.isFile) { "下载没有生成缓存文件" }
        if (expectedBytes == null) {
            check(partial.length() > 0L) { "下载内容为空" }
        } else if (partial.length() != expectedBytes) {
            throw MediaDownloadSizeException("下载内容大小与附件元数据不一致")
        }
        // 会话持有的安装器与最终的原子发布共享同一个关闭监视器。
        if (reservation == null) {
            install(partial, target)
        } else {
            reservation.commit { install(partial, target) }
        }
        target.setLastModified(System.currentTimeMillis())
        target
    } catch (error: Throwable) {
        partial.delete()
        throw error
    } finally {
        reservation?.close()
    }
}

/** 精确复制 [expectedBytes] 个字节，在超出声明上限或绝对上限之前就失败。 */
internal fun copyExactMediaDownload(
    input: InputStream,
    output: OutputStream,
    expectedBytes: Long,
    ensureActive: () -> Unit = {},
    onBytesCopied: (Long) -> Unit = {},
): Long {
    validateMediaDownloadSize(expectedBytes, AttachmentPolicy.MAX_UPLOAD_BYTES)
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        ensureActive()
        if (total > expectedBytes - read || total > AttachmentPolicy.MAX_UPLOAD_BYTES - read) {
            throw MediaDownloadSizeException("下载响应超过附件声明大小")
        }
        output.write(buffer, 0, read)
        total += read
        onBytesCopied(total)
    }
    if (total != expectedBytes) {
        throw MediaDownloadSizeException("下载响应大小与附件元数据不一致")
    }
    return total
}

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
