package com.virjar.tk.media

import com.virjar.tk.repository.FileOps
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal const val DEFAULT_DESKTOP_MEDIA_QUOTA_BYTES: Long = 500L * 1024L * 1024L

/**
 * 单个认证会话的统一媒体缓存。
 *
 * 缓存根目录已经由 [DesktopSessionResources] 按服务器和 uid 隔离；本类只接受
 * TeamTalk 附件引用，并再次通过 [FileOps.resolveUrl] 绑定当前服务器。缓存文件名
 * 只包含 SHA-256 和经过白名单过滤的扩展名，不信任远端或消息中的原始文件名。
 *
 * 不使用 SQLite：文件本身的 mtime 就是 LRU 访问时间。这样缓存索引与文件不会
 * 出现双写不一致，也不存在进程级 JDBC connection 的关闭和并发问题。
 */
internal class DesktopMediaCache(
    private val serverBaseUrl: String,
    private val credentialGate: DesktopCredentialGate,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val downloader: DesktopMediaDownloader = HttpDesktopMediaDownloader,
    private val quotaBytes: Long = DEFAULT_DESKTOP_MEDIA_QUOTA_BYTES,
) : Closeable {

    private data class DownloadFlight(
        val deferred: Deferred<File>,
        val progressListeners: CopyOnWriteArrayList<(Float) -> Unit>,
    )

    private val closed = AtomicBoolean(false)
    private val flights = mutableMapOf<String, DownloadFlight>()
    private val flightsMutex = Mutex()
    private val cleanupLock = Any()
    private val callbackGate = Any()

    init {
        require(quotaBytes > 0) { "媒体缓存配额必须大于 0" }
        require(cacheDir.mkdirs() || cacheDir.isDirectory) { "无法创建媒体缓存目录: $cacheDir" }
        removeAbandonedPartials()
        evictToQuota()
    }

    /** 返回缓存文件并刷新 LRU；会话关闭、未命中或文件已丢失时返回 null。 */
    fun cachedFile(reference: String, suggestedFileName: String? = null): File? {
        if (closed.get()) return null
        credentialGate.ensureOwner()
        val target = targetFile(reference, suggestedFileName)
        if (!target.isFile) return null
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    /**
     * 下载或复用已有缓存。同一会话内相同附件只有一个网络请求；所有等待者共享结果，
     * 且都能收到后续进度。下载先进入唯一 `.part` 文件，成功后才原子替换最终文件。
     */
    suspend fun ensureDownloaded(
        reference: String,
        suggestedFileName: String? = null,
        onProgress: (Float) -> Unit = {},
    ): File {
        ensureOpen()
        cachedFile(reference, suggestedFileName)?.let { return it }

        val key = cacheKey(reference)
        val flight = flightsMutex.withLock {
            ensureOpen()
            cachedFile(reference, suggestedFileName)?.let { return it }
            flights[key]?.also { existing ->
                existing.progressListeners += onProgress
            } ?: run {
                val listeners = CopyOnWriteArrayList<(Float) -> Unit>().apply { add(onProgress) }
                val deferred = scope.async {
                    downloadToCache(reference, suggestedFileName) { progress ->
                        dispatchProgress(listeners, progress)
                    }
                }
                DownloadFlight(deferred, listeners).also { created ->
                    flights[key] = created
                    created.deferred.invokeOnCompletion {
                        scope.launch {
                            flightsMutex.withLock {
                                if (flights[key] === created) flights.remove(key)
                            }
                        }
                    }
                }
            }
        }

        return try {
            flight.deferred.await()
        } finally {
            flight.progressListeners.remove(onProgress)
        }
    }

    /** 测试与会话启动使用的显式配额整理。 */
    fun evictToQuota() = synchronized(cleanupLock) {
        if (!cacheDir.isDirectory) return@synchronized
        val files = cacheDir.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
        var total = files.sumOf(File::length)
        if (total <= quotaBytes) return@synchronized

        // 清到配额的 80%，避免每次小文件落盘都触发一次扫描。
        val targetBytes = quotaBytes * 8 / 10
        files.sortedBy(File::lastModified).forEach { file ->
            if (total <= targetBytes) return@forEach
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    override fun close() {
        val shouldClose = synchronized(callbackGate) {
            closed.compareAndSet(false, true)
        }
        if (!shouldClose) return
        removeAbandonedPartials()
    }

    private suspend fun downloadToCache(
        reference: String,
        suggestedFileName: String?,
        onProgress: (Float) -> Unit,
    ): File {
        ensureOpen()
        coroutineContext.ensureActive()
        val target = targetFile(reference, suggestedFileName)
        cachedFile(reference, suggestedFileName)?.let { return it }
        val partial = Files.createTempFile(cacheDir.toPath(), "${cacheKey(reference)}-", PART_SUFFIX).toFile()

        try {
            val request = DesktopMediaDownloadRequest(
                resolvedUrl = FileOps.resolveUrl(serverBaseUrl, reference),
                authorizationToken = credentialGate.requireAccessToken(),
            )
            downloader.download(request, partial, onProgress)
            coroutineContext.ensureActive()
            credentialGate.ensureOwner()
            require(partial.isFile) { "媒体下载没有生成临时文件" }
            moveAtomically(partial, target)
            target.setLastModified(System.currentTimeMillis())
            onProgress(1f)
            evictToQuotaPreserving(target)
            AppLog.trace("MediaCache", "cached ${target.name} (${target.length()}B)")
            return target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun evictToQuotaPreserving(preserve: File) = synchronized(cleanupLock) {
        val files = cacheDir.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
        var total = files.sumOf(File::length)
        if (total <= quotaBytes) return@synchronized
        val targetBytes = quotaBytes * 8 / 10
        files.asSequence()
            .filterNot { it == preserve }
            .sortedBy(File::lastModified)
            .forEach { file ->
                if (total <= targetBytes) return@forEach
                val length = file.length()
                if (file.delete()) total -= length
            }
        // 单个文件可能大于配额；保留本次明确请求的文件，下一次会话启动再清理。
    }

    private fun targetFile(reference: String, suggestedFileName: String?): File {
        val extension = safeExtension(suggestedFileName ?: reference)
        return File(cacheDir, "${cacheKey(reference)}.$extension")
    }

    private fun cacheKey(reference: String): String =
        desktopSha256(FileOps.resolveUrl(serverBaseUrl, reference))

    private fun ensureOpen() {
        check(!closed.get()) { "Desktop 媒体缓存已经关闭" }
        credentialGate.ensureOwner()
    }

    /** close 返回前等待已进入的回调结束；close 返回后不再允许迟到进度触达 UI。 */
    private fun dispatchProgress(
        listeners: CopyOnWriteArrayList<(Float) -> Unit>,
        progress: Float,
    ) = synchronized(callbackGate) {
        if (closed.get()) return@synchronized
        runCatching { credentialGate.ensureOwner() }.getOrElse { return@synchronized }
        listeners.forEach { listener -> runCatching { listener(progress) } }
    }

    private fun removeAbandonedPartials() {
        cacheDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
            .forEach(File::delete)
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
        const val PART_SUFFIX = ".part"

        fun safeExtension(name: String): String {
            val candidate = name.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
                .lowercase()
                .filter { char -> char in 'a'..'z' || char in '0'..'9' }
                .take(10)
            return candidate.ifBlank { "bin" }
        }
    }
}

internal data class DesktopMediaDownloadRequest(
    val resolvedUrl: String,
    val authorizationToken: String,
)

internal fun interface DesktopMediaDownloader {
    suspend fun download(
        request: DesktopMediaDownloadRequest,
        partialFile: File,
        onProgress: (Float) -> Unit,
    ): Long
}

/** 不跟随重定向，避免认证 header 被带到其他主机。 */
internal object HttpDesktopMediaDownloader : DesktopMediaDownloader {
    override suspend fun download(
        request: DesktopMediaDownloadRequest,
        partialFile: File,
        onProgress: (Float) -> Unit,
    ): Long {
        val connection = (URL(request.resolvedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 120_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer ${request.authorizationToken}")
        }
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "下载失败 HTTP $code" }
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastReported = 0L
            connection.inputStream.use { input ->
                partialFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = downloaded
                            onProgress(progress(downloaded, total))
                        }
                    }
                }
            }
            return downloaded
        } finally {
            connection.disconnect()
        }
    }

    private fun progress(downloaded: Long, total: Long): Float =
        if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else -1f

    private const val PROGRESS_STEP_BYTES = 128L * 1024L
}

internal fun desktopSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
