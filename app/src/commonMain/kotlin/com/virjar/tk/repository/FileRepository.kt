package com.virjar.tk.repository

import com.virjar.tk.client.UserContext
import com.virjar.tk.util.buildFileUrl
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.Serializable

@Serializable
data class UploadResult(val path: String, val thumbnailPath: String = "")

@Serializable
private data class UploadResponse(val path: String = "", val thumbnailPath: String = "")

class FileRepository(private val ctx: UserContext) {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    // Download deduplication cache: path -> bytes
    private val downloadedFiles = mutableMapOf<String, ByteArray>()

    /**
     * Upload a file via multipart POST. Returns the server-assigned path and optional thumbnail path.
     */
    suspend fun uploadFile(
        bytes: ByteArray,
        fileName: String,
        contentType: String = "image/jpeg",
        onProgress: ((Float) -> Unit)? = null,
    ): UploadResult {
        val response: HttpResponse = ctx.httpClient.post("${ctx.baseUrl}/api/v1/files/upload") {
            header("Authorization", ctx.authHeader())
            setBody(MultiPartFormDataContent(
                formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, contentType)
                    })
                }
            ))
            onUpload { bytesSentTotal, contentLength ->
                if (contentLength != null && contentLength > 0 && onProgress != null) {
                    onProgress(bytesSentTotal.toFloat() / contentLength)
                }
            }
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw IllegalStateException("文件上传失败: ${response.status.value} $body")
        }
        val body = response.bodyAsText()
        val parsed = json.decodeFromString<UploadResponse>(body)
        return UploadResult(parsed.path, parsed.thumbnailPath)
    }

    /** Upload an image file. Convenience method. */
    suspend fun uploadImage(bytes: ByteArray, fileName: String = "image.jpg", onProgress: ((Float) -> Unit)? = null): UploadResult {
        val ext = fileName.substringAfterLast('.', "jpg").lowercase()
        val contentType = when (ext) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/jpeg"
        }
        return uploadFile(bytes, fileName, contentType, onProgress)
    }

    /** Build the full download URL from a server-assigned file path. */
    fun buildDownloadUrl(path: String): String = buildFileUrl(ctx.baseUrl, path)

    /** Download a file from the server. Returns the raw bytes. Uses in-memory cache for deduplication. */
    suspend fun downloadFile(path: String): ByteArray {
        synchronized(downloadedFiles) {
            downloadedFiles[path]?.let { return it }
        }
        val url = buildDownloadUrl(path)
        val bytes = java.net.URL(url).readBytes()
        synchronized(downloadedFiles) {
            downloadedFiles[path] = bytes
        }
        return bytes
    }
}
