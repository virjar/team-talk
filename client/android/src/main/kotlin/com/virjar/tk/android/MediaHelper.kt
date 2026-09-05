package com.virjar.tk.android

import com.virjar.tk.shared.AppError
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
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.http.UploadResult
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.repository.FileOps
import com.virjar.tk.shared.repository.asUploadSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection

/**
 * Android 选取附件的本地安全上限。
 *
 * 选择器返回的 URI 可能没有可信的长度元数据，因此读取过程仍会逐块核对这个上限。文件先落到
 * cacheDir 再流式上传，不会把最多 512 MiB 的视频整体放进 Java 堆。
 */
internal const val MAX_SELECTED_MEDIA_BYTES: Long = com.virjar.tk.protocol.body.AttachmentPolicy.MAX_UPLOAD_BYTES

/** HTTP 状态码是唯一输入；响应正文和凭证材料绝不会进入错误信息。 */
internal fun androidMediaDownloadHttpFailure(status: Int): AppError = when (status) {
    HttpURLConnection.HTTP_UNAUTHORIZED -> AppError.AuthExpired
    else -> AppError.Business(status, "下载失败（HTTP $status）")
}

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
    ): Attachment {
        val source = withContext(Dispatchers.IO) { file.asUploadSource() }
        return mediaSession.fileRepository.upload(source, fileName, contentType).getOrThrow()
    }

    /** 从磁盘流式上传并返回服务端媒体元数据。 */
    suspend fun uploadWithMeta(
        file: File,
        fileName: String,
        contentType: String,
        mediaSession: AndroidMediaSession,
    ): UploadResult {
        val source = withContext(Dispatchers.IO) { file.asUploadSource() }
        return mediaSession.fileRepository.uploadWithMeta(source, fileName, contentType).getOrThrow()
    }

    /** 在未知/失败的响应之后，使用调用方持有的身份重放一次逻辑上传。 */
    suspend fun uploadWithMeta(
        file: File,
        fileName: String,
        contentType: String,
        mediaSession: AndroidMediaSession,
        identity: AttachmentUploadIdentity,
    ): UploadResult {
        val source = withContext(Dispatchers.IO) { file.asUploadSource() }
        return mediaSession.fileRepository
            .uploadWithMeta(source, fileName, contentType, identity)
            .getOrThrow()
    }

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
        val operationContext = currentCoroutineContext()
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
            val size = mediaSession.withRegisteredOperation(input::close) {
                withCancellationAbort(input::close) {
                    input.use { source ->
                        target.outputStream().buffered().use { output ->
                            copyBounded(source, output, maxBytes) {
                                operationContext.ensureActive()
                                mediaSession.ensureOpen()
                            }
                        }
                    }
                }
            }
            operationContext.ensureActive()
            var prepared: PreparedMedia? = null
            check(mediaSession.runIfOpen {
                prepared = PreparedMedia(
                    file = target,
                    fileName = getFileName(context, uri),
                    contentType = getMimeType(context, uri),
                    size = size,
                )
            }) { "媒体会话已经关闭" }
            checkNotNull(prepared)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /**
     * 下载或复用附件，并返回已固定的完整本地文件。
     * 原生消费者持有该租约，直到其 release 调用完成；不会向容量 LRU 暴露裸文件间隙。
     * 强制刷新会先移除未固定的缓存副本。
     */
    internal suspend fun downloadToCacheLease(
        attachment: Attachment,
        context: Context,
        mediaSession: AndroidMediaSession,
        forceRefresh: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
    ): AndroidMediaCacheFileLease = downloadToCacheLease(
        attachment = attachment,
        cacheDir = resolveAndroidMediaCacheRoot(context),
        mediaSession = mediaSession,
        forceRefresh = forceRefresh,
        onProgress = onProgress,
    )

    internal suspend fun downloadToCacheLease(
        attachment: Attachment,
        cacheDir: File,
        mediaSession: AndroidMediaSession,
        forceRefresh: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
    ): AndroidMediaCacheFileLease = withCloseableContext(Dispatchers.IO) {
        mediaSession.ensureOpen()
        validateMediaDownloadSize(attachment.size, DEFAULT_ANDROID_MEDIA_CACHE_QUOTA_BYTES)
        val operationContext = currentCoroutineContext()
        val resolvedUrl = FileOps.resolveUrl(mediaSession.serverUrl, attachment)
        val hash = sha256Hex(resolvedUrl)
        val ext = attachment.name.substringAfterLast('.', "").let { if (it.length <= 5) it else "bin" }
        val directory = ensureManagedMediaCacheDirectory(
            cacheDir,
            mediaSession.cacheNamespace,
            "downloads",
        )
        val file = File(directory, "$hash.$ext")
        val lease = materializePinnedMediaCacheFile(
            target = file,
            expectedBytes = attachment.size,
            acquireCachedLease = {
                AndroidMediaCacheCapacityRegistry.cachedLease(
                    cacheRoot = cacheDir,
                    file = file,
                    expectedBytes = attachment.size,
                    forceRefresh = forceRefresh,
                )
            },
            reserveCapacity = {
                AndroidMediaCacheCapacityRegistry.reserve(
                    cacheRoot = cacheDir,
                    expectedBytes = attachment.size,
                    target = file,
                )
            },
            install = mediaSession::installCacheFile,
        ) { partial ->
            downloadAttachmentToPartial(
                attachment = attachment,
                resolvedUrl = resolvedUrl,
                partial = partial,
                mediaSession = mediaSession,
                onProgress = onProgress,
            )
        }
        try {
            operationContext.ensureActive()
            check(mediaSession.runIfOpen { onProgress?.invoke(1f) }) {
                "媒体会话已经关闭"
            }
            lease
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
    }

    private suspend fun downloadAttachmentToPartial(
        attachment: Attachment,
        resolvedUrl: String,
        partial: File,
        mediaSession: AndroidMediaSession,
        onProgress: ((Float) -> Unit)?,
    ) {
        val operationContext = currentCoroutineContext()
        mediaSession.withAuthenticatedConnection(
            url = resolvedUrl,
            configure = { conn ->
                conn.connectTimeout = 10_000
                conn.readTimeout = 60_000
            },
        ) { conn ->
            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw androidMediaDownloadHttpFailure(responseCode)
            }
            validateMediaResponseLength(conn.contentLengthLong, attachment.size)
            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    copyExactMediaDownload(
                        input = input,
                        output = output,
                        expectedBytes = attachment.size,
                        ensureActive = {
                            operationContext.ensureActive()
                            mediaSession.ensureOpen()
                        },
                    ) { downloaded ->
                        if (attachment.size > 0L) {
                            mediaSession.runIfOpen {
                                onProgress?.invoke(downloaded.toFloat() / attachment.size)
                            }
                        }
                    }
                }
            }
        }
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            null
        } finally {
            releaseAndroidMediaMetadata(retriever::release)
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
            val decoded = when {
                supportsScaledVideoThumbnail() -> {
                    val (targetWidth, targetHeight) = scaledVideoThumbnailSize(sourceWidth, sourceHeight)
                    getScaledVideoFrameAtTime(retriever, targetWidth, targetHeight)
                }
                // API 26 只有 getFrameAtTime，而平台旧版 ThumbnailUtils 内部也可能先解码
                // 全尺寸帧。缩略图只是可选元数据，因此明确降级为空，避免 OOM。
                else -> return null
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            null
        } finally {
            releaseAndroidMediaMetadata(retriever::release)
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

}

/** 原生元数据清理可以降级普通状态错误，但绝不能吞掉取消或 VM 缺陷。 */
internal fun releaseAndroidMediaMetadata(release: () -> Unit) {
    try {
        release()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // 元数据是可选的，而且越过此边界之后 retriever 已没有可用的所有者。
    }
}

/** 逐块复制并在写入第 maxBytes + 1 个字节前失败。 */
internal fun copyBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
    ensureActive: () -> Unit = {},
): Long {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        ensureActive()
        val read = input.read(buffer)
        if (read < 0) return total
        if (read == 0) continue
        ensureActive()
        if (total > maxBytes - read) throw SelectedMediaTooLargeException(maxBytes)
        output.write(buffer, 0, read)
        total += read
    }
}

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

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O_MR1)
private fun supportsScaledVideoThumbnail(): Boolean =
    videoThumbnailStrategyForSdk(Build.VERSION.SDK_INT) == VideoThumbnailDecodeStrategy.ScaledRetriever

@RequiresApi(Build.VERSION_CODES.O_MR1)
private fun getScaledVideoFrameAtTime(
    retriever: MediaMetadataRetriever,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap? = retriever.getScaledFrameAtTime(
    -1,
    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
    targetWidth,
    targetHeight,
)

private fun constrainVideoThumbnail(bitmap: Bitmap): Bitmap {
    val (width, height) = scaledVideoThumbnailSize(bitmap.width, bitmap.height)
    if (width == bitmap.width && height == bitmap.height) return bitmap
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}
