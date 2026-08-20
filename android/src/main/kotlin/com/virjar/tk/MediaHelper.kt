package com.virjar.tk

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.virjar.tk.AppError
import com.virjar.tk.client.SessionHttpCredentials
import com.virjar.tk.http.UploadResult
import com.virjar.tk.model.Attachment
import com.virjar.tk.repository.FileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Android 选取附件的本地安全上限。
 *
 * 选择器返回的 URI 可能没有可信的长度元数据，因此读取过程仍会逐块核对这个上限。文件先落到
 * cacheDir 再流式上传，不会把最多 512 MiB 的视频整体放进 Java 堆。
 */
internal const val MAX_SELECTED_MEDIA_BYTES: Long = 512L * 1024 * 1024

/**
 * 生成不暴露服务器或 uid 的账号缓存命名空间。Bearer token 轮换不改变同一服务器、同一账号的
 * 缓存身份；跨账号和跨服务器仍严格隔离。
 */
internal fun mediaCacheNamespace(
    serverUrl: String,
    ownerUid: String,
): String {
    val serverIdentity = serverUrl.trim().trimEnd('/')
    require(serverIdentity.isNotBlank()) { "media server identity must not be empty" }
    require(ownerUid.isNotBlank()) { "media owner uid must not be empty" }
    return sha256Hex("teamtalk-media-v2\u0000$serverIdentity\u0000uid\u0000$ownerUid").take(32)
}

/**
 * Immutable credentials captured when an authenticated Android UI session is composed.
 *
 * Passing this value through every protected-media operation makes it impossible to pair account
 * A's cache namespace with a token read later from account B's mutable login state.
 */
class AndroidMediaSession private constructor(
    val serverUrl: String,
    private val ownerUid: String,
    private val credentialsProvider: () -> SessionHttpCredentials,
    val cacheNamespace: String,
) {
    /**
     * Reads a reconnect-rotated token while refusing a later login that reused the UserSession
     * object for another uid. The returned token is fixed by each HTTP request before IO begins.
     */
    fun accessTokenForRequest(): String {
        val credentials = credentialsProvider()
        check(credentials.uid == ownerUid) { "媒体任务所属登录会话已失效" }
        return credentials.accessToken?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("认证凭据不可用，请重新登录")
    }

    fun isCurrentOwner(): Boolean = credentialsProvider().uid == ownerUid

    companion object {
        fun create(
            serverUrl: String,
            ownerUid: String,
            credentialsProvider: () -> SessionHttpCredentials,
        ): AndroidMediaSession {
            val normalizedServerUrl = serverUrl.trim().trimEnd('/')
            return AndroidMediaSession(
                serverUrl = normalizedServerUrl,
                ownerUid = ownerUid,
                credentialsProvider = credentialsProvider,
                cacheNamespace = mediaCacheNamespace(
                    serverUrl = normalizedServerUrl,
                    ownerUid = ownerUid,
                ),
            )
        }
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
    if (category == "attachments") {
        // FileProvider XML 无法表达 teamtalk-media/<动态 scope>/attachments 通配路径。
        // 把可分享文件集中到固定的窄前缀，避免授权整个 teamtalk-media 或 cacheDir。
        return File(cacheRoot, "$FILE_PROVIDER_ATTACHMENTS_PATH$opaqueScope")
    }
    return File(cacheRoot, "teamtalk-media/$opaqueScope/$category")
}

/** 只删除指定会话命名空间；不会触碰其他账号目录或历史全局缓存。 */
private fun deleteMediaCacheNamespace(cacheRoot: File, cacheNamespace: String): Boolean {
    val namespaceRoot = mediaCacheDirectory(
        cacheRoot,
        cacheNamespace,
        "session-root",
    ).parentFile ?: return true
    val attachmentsRoot = mediaCacheDirectory(cacheRoot, cacheNamespace, "attachments")
    val namespaceDeleted = !namespaceRoot.exists() || namespaceRoot.deleteRecursively()
    val attachmentsDeleted = !attachmentsRoot.exists() || attachmentsRoot.deleteRecursively()
    return namespaceDeleted && attachmentsDeleted
}

/**
 * 强制清理指定会话缓存。清理会等待所有媒体目标写入退出，不会与 `.part` 落盘竞争。
 * 普通页面销毁应释放 [MediaCacheLease]，不应直接调用此函数。
 */
internal suspend fun clearMediaCacheNamespace(cacheRoot: File, cacheNamespace: String): Boolean =
    MediaCacheWriteCoordinator.withAllTargets {
        deleteMediaCacheNamespace(cacheRoot, cacheNamespace)
    }

/** 同一登录会话下的页面缓存所有权；重复关闭是安全的。 */
internal class MediaCacheLease internal constructor(
    private val key: MediaCacheLeaseRegistry.Key,
    private val state: MediaCacheLeaseRegistry.State,
) {
    private val closed = AtomicBoolean(false)

    fun close(): Job? = if (closed.compareAndSet(false, true)) {
        MediaCacheLeaseRegistry.release(key, state)
    } else {
        null
    }
}

internal fun acquireMediaCacheLease(cacheRoot: File, cacheNamespace: String): MediaCacheLease =
    MediaCacheLeaseRegistry.acquire(cacheRoot, cacheNamespace)

/**
 * 账号命名空间会被多个聊天/群文件页共享。只有最后一个所有者退出后才可以清理；
 * 新页面在清理检查前获取租约会取消该次清理。
 */
internal object MediaCacheLeaseRegistry {
    internal data class Key(val cacheRootPath: String, val cacheNamespace: String)
    internal class State(
        var owners: Int = 0,
        val lifecycleLock: ReentrantLock = ReentrantLock(),
    )

    private val states = mutableMapOf<Key, State>()
    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(cleanupJob + Dispatchers.IO)

    fun acquire(cacheRoot: File, cacheNamespace: String): MediaCacheLease {
        val key = Key(cacheRoot.absoluteFile.normalize().path, cacheNamespace)
        while (true) {
            val state = synchronized(this) { states.getOrPut(key) { State() } }
            val acquired = state.lifecycleLock.withLock {
                synchronized(this) {
                    if (states[key] !== state) {
                        false
                    } else {
                        state.owners += 1
                        true
                    }
                }
            }
            if (acquired) return MediaCacheLease(key, state)
        }
    }

    fun release(key: Key, state: State): Job? {
        val shouldClean = synchronized(this) {
            if (states[key] !== state || state.owners <= 0) {
                false
            } else {
                state.owners -= 1
                state.owners == 0
            }
        }
        if (!shouldClean) return null

        return cleanupScope.launch {
            MediaCacheWriteCoordinator.withAllTargets {
                state.lifecycleLock.withLock {
                    val stillUnowned = synchronized(this@MediaCacheLeaseRegistry) {
                        states[key] === state && state.owners == 0
                    }
                    if (!stillUnowned) return@withLock

                    deleteMediaCacheNamespace(File(key.cacheRootPath), key.cacheNamespace)
                    synchronized(this@MediaCacheLeaseRegistry) {
                        if (states[key] === state && state.owners == 0) {
                            states.remove(key)
                        }
                    }
                }
            }
        }
    }

    /** Process-owner teardown; normal page disposal releases an individual lease instead. */
    fun close() {
        cleanupJob.cancel()
        synchronized(this) { states.clear() }
    }
}

internal const val FILE_PROVIDER_ATTACHMENTS_PATH = "teamtalk-media/attachments/"

internal class SelectedMediaTooLargeException(
    val maxBytes: Long,
) : IllegalArgumentException("所选文件不能超过 ${maxBytes / (1024 * 1024)} MB")

/** 选择器 URI 的稳定快照；调用方上传结束后必须 [delete]。 */
internal data class PreparedMedia(
    val file: File,
    val fileName: String,
    val contentType: String,
    val size: Long,
) {
    fun delete() {
        if (file.exists()) file.delete()
    }
}

/**
 * Android 媒体工具：有界文件上传、视频下载缓存与元数据提取。
 */
object MediaHelper {
    /** 从磁盘流式上传，避免 Android 选择的大文件再复制成 ByteArray。 */
    suspend fun uploadFile(
        file: File,
        fileName: String,
        contentType: String,
        mediaSession: AndroidMediaSession,
    ): Attachment = uploadFileStreaming(file, fileName, contentType, mediaSession).file

    /** 从磁盘流式上传并返回服务端媒体元数据。 */
    suspend fun uploadWithMeta(
        file: File,
        fileName: String,
        contentType: String,
        mediaSession: AndroidMediaSession,
    ): UploadResult = uploadFileStreaming(file, fileName, contentType, mediaSession)

    /**
     * 在 IO 线程把一次系统选择固化为有界临时文件。
     *
     * 先检查 provider 声明的长度用于快速失败；即使 provider 未声明或声明错误，复制循环也会在
     * 写入超过上限前中止。这样既不会在 Compose 主线程读取，也不会信任外部 ContentProvider。
     */
    internal suspend fun prepareSelectedMedia(
        context: Context,
        uri: Uri,
        mediaSession: AndroidMediaSession,
        maxBytes: Long = MAX_SELECTED_MEDIA_BYTES,
    ): PreparedMedia = withContext(Dispatchers.IO) {
        mediaSession.accessTokenForRequest()
        require(maxBytes > 0) { "maxBytes must be positive" }
        val declaredSize = getFileSizeOrNull(context, uri)
        if (declaredSize != null && declaredSize > maxBytes) {
            throw SelectedMediaTooLargeException(maxBytes)
        }

        val directory = mediaCacheDirectory(
            context.cacheDir,
            mediaSession.cacheNamespace,
            "outgoing-media",
        ).apply { mkdirs() }
        val target = File.createTempFile("selected-", ".tmp", directory)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            val size = input.use { source ->
                target.outputStream().buffered().use { output ->
                    copyBounded(source, output, maxBytes)
                }
            }
            PreparedMedia(
                file = target,
                fileName = getFileName(context, uri),
                contentType = getMimeType(context, uri),
                size = size,
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /**
     * 下载视频到本地缓存目录。
     * 以 URL 的 SHA-256 为缓存键，并限制在当前认证会话目录内，避免跨账号复用。
     * @return 本地缓存文件路径
     */
    suspend fun downloadToCache(
        url: String,
        cacheDir: File,
        mediaSession: AndroidMediaSession,
        onProgress: ((Float) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val accessToken = mediaSession.accessTokenForRequest()
        val hash = sha256Hex(url)
        val ext = url.substringAfterLast('.', "").let { if (it.length <= 5) it else "mp4" }
        val directory = mediaCacheDirectory(cacheDir, mediaSession.cacheNamespace, "downloads").apply { mkdirs() }
        val file = File(directory, "$hash.$ext")
        val cached = materializeMediaCacheFile(file) { partial ->
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            try {
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    error("下载失败 HTTP $responseCode")
                }
                val total = conn.contentLengthLong
                var downloaded = 0L
                conn.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (total > 0) {
                                onProgress?.invoke(downloaded.toFloat() / total)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        onProgress?.invoke(1f)
        cached
    }

    /**
     * 从 Uri 读取文件名。
     */
    fun getFileName(context: Context, uri: Uri): String {
        var name = "unknown"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: "unknown"
            }
        }
        if (name == "unknown") {
            name = uri.lastPathSegment ?: "unknown"
        }
        return name
    }

    /**
     * 从 Uri 读取文件大小。
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        return getFileSizeOrNull(context, uri) ?: 0L
    }

    private fun getFileSizeOrNull(context: Context, uri: Uri): Long? {
        var size: Long? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        return size
    }

    /**
     * 从 Uri 读取 MIME 类型。
     */
    fun getMimeType(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            )
            ?: "application/octet-stream"
    }

    /**
     * 提取视频元数据（时长秒、宽、高）。
     */
    fun getVideoMetadata(context: Context, uri: Uri): Triple<Int, Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong()?.div(1000)?.toInt() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            Triple(duration, width, height)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 提取视频首帧缩略图，保存为 JPEG 临时文件。
     * @return 临时文件，失败返回 null
     */
    fun extractVideoThumbnail(
        context: Context,
        sourceFile: File,
        mediaSession: AndroidMediaSession,
    ): File? {
        mediaSession.accessTokenForRequest()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(sourceFile.absolutePath)
            val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val decoded = when (videoThumbnailStrategyForSdk(Build.VERSION.SDK_INT)) {
                VideoThumbnailDecodeStrategy.ScaledRetriever -> {
                    val (targetWidth, targetHeight) = scaledVideoThumbnailSize(sourceWidth, sourceHeight)
                    retriever.getScaledFrameAtTime(
                        -1,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        targetHeight,
                    )
                }
                // API 26 只有 getFrameAtTime，而平台旧版 ThumbnailUtils 内部也可能先解码
                // 全尺寸帧。缩略图只是可选元数据，因此明确降级为空，避免 OOM。
                VideoThumbnailDecodeStrategy.SkipLocalFrame -> return null
            } ?: return null
            val bitmap = constrainVideoThumbnail(decoded)
            if (bitmap !== decoded) decoded.recycle()
            val directory = mediaCacheDirectory(
                context.cacheDir,
                mediaSession.cacheNamespace,
                "outgoing-thumbnails",
            ).apply { mkdirs() }
            val file = File.createTempFile("thumb-", ".jpg", directory)
            try {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            } finally {
                bitmap.recycle()
            }
            file
        } catch (_: OutOfMemoryError) {
            // 缩略图是可选元数据；低内存设备直接降级为无缩略图，不能拖垮发送流程。
            null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 用系统应用打开文件。
     */
    fun openFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (fileOpenRequiresNewTask(context.containsActivity())) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    private suspend fun uploadFileStreaming(
        file: File,
        fileName: String,
        contentType: String,
        mediaSession: AndroidMediaSession,
    ): UploadResult = withContext(Dispatchers.IO) {
        val accessToken = mediaSession.accessTokenForRequest()
        require(file.isFile) { "待上传文件不存在" }

        val boundary = "TeamTalk-${UUID.randomUUID()}"
        val safeName = sanitizeHeaderValue(fileName).ifBlank { "attachment" }.take(255)
        val safeType = sanitizeContentType(contentType)
        val prefix = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"")
            append(safeName).append("\"\r\n")
            append("Content-Type: ").append(safeType).append("\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val requestLength = prefix.size.toLong() + file.length() + suffix.size.toLong()

        val connection = (URL("${mediaSession.serverUrl}/api/v1/files/upload")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setFixedLengthStreamingMode(requestLength)
        }

        try {
            BufferedOutputStream(connection.outputStream, 64 * 1024).use { output ->
                output.write(prefix)
                file.inputStream().buffered().use { input -> input.copyTo(output, 64 * 1024) }
                output.write(suffix)
            }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { input -> String(readUploadResponseBounded(input), StandardCharsets.UTF_8) }
                .orEmpty()
            if (code !in 200..299) {
                throw AppError.Business(code, "上传失败（HTTP $code）")
            }
            FileOps.parseUploadResult(response)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * 同一缓存目标只允许一个写入者。固定数量的分片锁避免 URL 数量增长时积累锁对象。
 * 后到的缩略图/画廊请求在获锁后会直接命中首个请求已经落盘的文件。
 */
private object MediaCacheWriteCoordinator {
    private val locks = Array(64) { Mutex() }

    suspend fun <T> withTarget(target: File, action: suspend () -> T): T {
        val index = Math.floorMod(target.absolutePath.hashCode(), locks.size)
        return locks[index].withLock { action() }
    }

    suspend fun <T> withAllTargets(action: suspend () -> T): T = withLocksFrom(0, action)

    private suspend fun <T> withLocksFrom(index: Int, action: suspend () -> T): T {
        if (index == locks.size) return action()
        return locks[index].withLock { withLocksFrom(index + 1, action) }
    }
}

/**
 * 以唯一临时文件写入，再在同一目录内原子改名为最终缓存。
 * 最终文件永远不会暴露半成品，失败或取消也只会清理本次的临时文件。
 */
internal suspend fun materializeMediaCacheFile(
    target: File,
    writePartial: suspend (File) -> Unit,
): File = MediaCacheWriteCoordinator.withTarget(target) {
    if (target.isFile && target.length() > 0L) return@withTarget target

    target.parentFile?.mkdirs()
    if (target.exists() && !target.delete()) {
        error("无法替换损坏的媒体缓存")
    }
    val partial = File.createTempFile("${target.name}.", ".part", target.parentFile)
    try {
        writePartial(partial)
        check(partial.isFile && partial.length() > 0L) { "下载内容为空" }
        // partial 与 target 在同一目录，rename 不会向读取方暴露半成品。
        check(partial.renameTo(target)) { "无法原子落盘媒体缓存" }
        target
    } catch (error: Throwable) {
        partial.delete()
        throw error
    }
}

private const val MAX_UPLOAD_RESPONSE_BYTES = 1024L * 1024

/** 逐块复制并在写入第 maxBytes + 1 个字节前失败。 */
internal fun copyBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
): Long {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return total
        if (read == 0) continue
        if (total > maxBytes - read) throw SelectedMediaTooLargeException(maxBytes)
        output.write(buffer, 0, read)
        total += read
    }
}

internal fun readBytesBounded(input: InputStream, maxBytes: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024L).toInt())
    copyBounded(input, output, maxBytes)
    return output.toByteArray()
}

private fun readUploadResponseBounded(input: InputStream): ByteArray = try {
    readBytesBounded(input, MAX_UPLOAD_RESPONSE_BYTES)
} catch (_: SelectedMediaTooLargeException) {
    throw IllegalStateException("上传响应超过安全上限")
}

private fun sanitizeHeaderValue(value: String): String = value
    .replace('\r', '_')
    .replace('\n', '_')
    .replace('"', '_')
    .replace('\\', '_')

private fun sanitizeContentType(value: String): String = value
    .trim()
    .takeIf { candidate ->
        candidate.isNotEmpty() && candidate.all { it.isLetterOrDigit() || it in "!#$&^_.+-/" }
    }
    ?: "application/octet-stream"

internal fun fileOpenRequiresNewTask(isActivityContext: Boolean): Boolean = !isActivityContext

private tailrec fun Context.containsActivity(): Boolean = when (this) {
    is Activity -> true
    is ContextWrapper -> baseContext.containsActivity()
    else -> false
}

internal fun scaledVideoThumbnailSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 512 to 512
    val scale = minOf(1.0, 512.0 / sourceWidth, 512.0 / sourceHeight)
    return (sourceWidth * scale).toInt().coerceAtLeast(1) to
        (sourceHeight * scale).toInt().coerceAtLeast(1)
}

internal enum class VideoThumbnailDecodeStrategy {
    ScaledRetriever,
    SkipLocalFrame,
}

internal fun videoThumbnailStrategyForSdk(sdkInt: Int): VideoThumbnailDecodeStrategy =
    if (sdkInt >= Build.VERSION_CODES.O_MR1) {
        VideoThumbnailDecodeStrategy.ScaledRetriever
    } else {
        VideoThumbnailDecodeStrategy.SkipLocalFrame
    }

private fun constrainVideoThumbnail(bitmap: Bitmap): Bitmap {
    val (width, height) = scaledVideoThumbnailSize(bitmap.width, bitmap.height)
    if (width == bitmap.width && height == bitmap.height) return bitmap
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
