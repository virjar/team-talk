package com.virjar.tk.android

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.virjar.tk.protocol.model.Attachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * 导出完整本地附件副本：图片/视频写入 Pictures/Movies，其他文件写入 Download。
 * Android 10+ 使用 MediaStore 的 pending 条目；8/9 在调用方取得存储权限后写公共目录。
 * 失败或取消删除未完成副本；原账号缓存保留，重名文件不覆盖。
 */
internal suspend fun exportAndroidAttachmentToUserLocation(
    context: Context,
    file: File,
    attachment: Attachment,
    workerDispatcher: CoroutineDispatcher,
    ensureOwnerOpen: () -> Unit,
): Boolean = withContext(workerDispatcher) {
    val operationContext = currentCoroutineContext()
    fun checkActive() {
        operationContext.ensureActive()
        ensureOwnerOpen()
    }
    fun copyTo(output: OutputStream) {
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        checkActive()
    }
    val isImage = attachment.contentType.startsWith("image/")
    val isVideo = attachment.contentType.startsWith("video/")
    val directory = when {
        isImage -> Environment.DIRECTORY_PICTURES
        isVideo -> Environment.DIRECTORY_MOVIES
        else -> Environment.DIRECTORY_DOWNLOADS
    }
    val displayName = attachment.name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001F]"), "_").take(120)
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
    try {
        checkActive()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val folder = Environment.getExternalStoragePublicDirectory(directory)
            check(folder.isDirectory || folder.mkdirs()) { "Cannot create export directory" }
            val destination = createUnusedExportFile(folder, displayName)
            try {
                destination.outputStream().use(::copyTo)
                checkActive()
                MediaScannerConnection.scanFile(
                    context, arrayOf(destination.absolutePath), arrayOf(attachment.contentType), null,
                )
            } catch (failure: Throwable) {
                destination.delete()
                throw failure
            }
        } else {
            val resolver = context.contentResolver
            val collection = when {
                isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, attachment.contentType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@withContext false
            try {
                checkNotNull(resolver.openOutputStream(uri)) { "Cannot open export destination" }.use(::copyTo)
                checkActive()
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(uri, published, null, null) == 1) { "Cannot publish export" }
            } catch (failure: Throwable) {
                try {
                    resolver.delete(uri, null, null)
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private fun createUnusedExportFile(directory: File, displayName: String): File {
    val extension = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    val stem = displayName.removeSuffix(extension)
    for (suffix in 0..9999) {
        val name = if (suffix == 0) displayName else "$stem ($suffix)$extension"
        val candidate = File(directory, name)
        if (candidate.createNewFile()) return candidate
    }
    error("Too many exported files with the same name")
}
