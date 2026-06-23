package com.virjar.tk

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import io.ktor.client.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Android 媒体工具：文件上传（带进度）、视频下载缓存、元数据提取。
 */
object MediaHelper {

    private val httpClient = HttpClient()

    /**
     * 上传进度回调。
     * @param bytesSent 已发送字节
     * @param totalBytes 总字节
     */
    /**
     * 上传文件到服务端，返回相对路径。
     * @param onProgress 上传进度回调（在 IO 线程调用，UI 更新需切线程）
     */
    suspend fun uploadFile(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
        serverUrl: String,
    ): String = withContext(Dispatchers.IO) {
        val response = httpClient.submitFormWithBinaryData(
            url = "$serverUrl/api/v1/files/upload",
            formData = formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            },
        )
        parseUploadResponse(response)
    }

    private suspend fun parseUploadResponse(response: HttpResponse): String {
        if (response.status != HttpStatusCode.OK) {
            throw RuntimeException("Upload failed: ${response.status}")
        }
        val body = response.bodyAsText()
        return Json.parseToJsonElement(body)
            .jsonObject["path"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Invalid upload response: $body")
    }

    /**
     * 下载视频到本地缓存目录。
     * 以 URL 的 MD5 为缓存键，避免重复下载。
     * @return 本地缓存文件路径
     */
    suspend fun downloadToCache(
        url: String,
        cacheDir: File,
        onProgress: ((Float) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val hash = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val ext = url.substringAfterLast('.', "").let { if (it.length <= 5) it else "mp4" }
        val file = File(cacheDir, "videos/$hash.$ext")

        // 已缓存，直接返回
        if (file.exists() && file.length() > 0) {
            onProgress?.invoke(1f)
            return@withContext file
        }

        file.parentFile?.mkdirs()

        // 下载
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        val total = conn.contentLengthLong
        var downloaded = 0L

        conn.inputStream.use { input ->
            file.outputStream().use { output ->
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
        onProgress?.invoke(1f)
        file
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
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst()) {
                size = cursor.getLong(sizeIndex)
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
     * 从 Uri 读取文件内容为 ByteArray。
     */
    fun readBytes(context: Context, uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw RuntimeException("Cannot read file: $uri")
    }

    /**
     * 提取视频元数据（时长秒、宽、高）。
     */
    fun getVideoMetadata(context: Context, uri: Uri): Triple<Int, Int, Int>? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong()?.div(1000)?.toInt() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            retriever.release()
            Triple(duration, width, height)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 提取视频首帧缩略图，保存为 JPEG 临时文件。
     * @return 临时文件，失败返回 null
     */
    fun extractVideoThumbnail(context: android.content.Context, uri: android.net.Uri): java.io.File? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val bitmap = retriever.frameAtTime ?: return null.also { retriever.release() }
            retriever.release()
            val file = java.io.File(context.cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
            file
        } catch (e: Exception) {
            null
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
        }
        context.startActivity(intent)
    }
}
