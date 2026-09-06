package com.virjar.tk.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.virjar.tk.protocol.model.Attachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把已下载的聊天附件导出到用户可见位置（T007）：
 * 图片/视频写入系统相册（Pictures/Movies），其他文件写入下载目录。
 *
 * Android 10+ 走 MediaStore 分区存储，无需权限；Android 9 及以下需要
 * WRITE_EXTERNAL_STORAGE，未授权时插入失败并返回 false（界面提示保存失败）。
 * 导出的是完整本地文件副本；应用内缓存与账号数据不受影响。
 */
internal suspend fun exportAndroidAttachmentToUserLocation(
    context: Context,
    file: File,
    attachment: Attachment,
    workerDispatcher: CoroutineDispatcher,
): Boolean = withContext(workerDispatcher) {
    try {
        val resolver = context.contentResolver
        val isImage = attachment.contentType.startsWith("image/")
        val isVideo = attachment.contentType.startsWith("video/")
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        val collection: Uri = when {
            isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            !legacy -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        val directory = when {
            isImage -> Environment.DIRECTORY_PICTURES
            isVideo -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, attachment.name)
            put(MediaStore.MediaColumns.MIME_TYPE, attachment.contentType)
            if (!legacy) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return@withContext false
        val copied = try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } != null
            if (legacy) {
                true
            } else {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            }
        } catch (thrown: Exception) {
            resolver.delete(uri, null, null)
            throw thrown
        }
        copied
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
}
