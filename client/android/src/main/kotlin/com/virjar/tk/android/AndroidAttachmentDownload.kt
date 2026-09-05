package com.virjar.tk.android

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.repository.FileOps
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun attachmentCacheFile(
    cacheRoot: File,
    cacheNamespace: String,
    attachment: Attachment,
): File {
    val directory = ensureManagedMediaCacheDirectory(cacheRoot, cacheNamespace, "attachments")
    val leaf = attachment.name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .take(120)
        .ifBlank { "attachment" }
    val key = sha256Hex(attachment.path).take(32)
    return File(directory, "$key-$leaf")
}

internal fun isValidAttachmentCacheFile(file: File, attachment: Attachment): Boolean =
    attachment.size in 0L..com.virjar.tk.protocol.body.AttachmentPolicy.MAX_UPLOAD_BYTES &&
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
        file.length() == attachment.size

/** 聊天附件与内嵌预览共用的认证下载和原子缓存路径；调用方负责关闭消费租约。 */
internal suspend fun downloadAttachmentToCacheLease(
    cacheRoot: File,
    mediaSession: AndroidMediaSession,
    attachment: Attachment,
    cacheQuotaBytes: Long = DEFAULT_ANDROID_MEDIA_CACHE_QUOTA_BYTES,
    cacheMaxEntries: Int = DEFAULT_ANDROID_MEDIA_CACHE_MAX_ENTRIES,
    onProgress: ((Float) -> Unit)? = null,
): AndroidMediaCacheFileLease = withCloseableContext(Dispatchers.IO) {
    mediaSession.ensureOpen()
    validateMediaDownloadSize(attachment.size, cacheQuotaBytes, cacheMaxEntries)
    val operationContext = currentCoroutineContext()
    val target = attachmentCacheFile(cacheRoot, mediaSession.cacheNamespace, attachment)
    val lease = materializePinnedMediaCacheFile(
        target = target,
        expectedBytes = attachment.size,
        acquireCachedLease = {
            AndroidMediaCacheCapacityRegistry.cachedLease(
                cacheRoot = cacheRoot,
                file = target,
                expectedBytes = attachment.size,
                quotaBytes = cacheQuotaBytes,
                maxEntries = cacheMaxEntries,
            )
        },
        reserveCapacity = {
            AndroidMediaCacheCapacityRegistry.reserve(
                cacheRoot = cacheRoot,
                expectedBytes = attachment.size,
                target = target,
                quotaBytes = cacheQuotaBytes,
                maxEntries = cacheMaxEntries,
            )
        },
        install = mediaSession::installCacheFile,
    ) { partial ->
        mediaSession.withAuthenticatedConnection(
            url = FileOps.resolveUrl(mediaSession.serverUrl, attachment),
            configure = { connection ->
                connection.connectTimeout = 10_000
                connection.readTimeout = 120_000
            },
        ) { connection ->
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw androidMediaDownloadHttpFailure(code)
            val total = connection.contentLengthLong
            validateMediaResponseLength(total, attachment.size)
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    var lastEmit = 0L
                    copyExactMediaDownload(
                        input = input,
                        output = output,
                        expectedBytes = attachment.size,
                        ensureActive = {
                            operationContext.ensureActive()
                            mediaSession.ensureOpen()
                        },
                    ) { downloaded ->
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 100L) {
                            lastEmit = now
                            mediaSession.runIfOpen {
                                onProgress?.invoke(
                                    if (attachment.size > 0L) downloaded.toFloat() / attachment.size else 1f,
                                )
                            }
                        }
                    }
                }
            }
        }
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
