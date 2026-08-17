package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.outcome
import io.ktor.client.*
import io.ktor.client.request.forms.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.statement.*
import io.ktor.client.request.headers
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 端 [FileRepository] 实现 —— 上传用 Ktor [HttpClient]，
 * 下载与 URL 拼装复用 [FileOps]（两端逻辑一致）。
 */
actual class FileRepository actual constructor(
    private val serverUrl: String,
    private val accessToken: String?,
) {

    private val httpClient = HttpClient()

    actual suspend fun upload(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): Outcome<String> = withContext(Dispatchers.IO) {
        outcome {
            uploadRaw(bytes, fileName, contentType).path
        }
    }

    actual suspend fun uploadWithMeta(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): Outcome<UploadResult> = withContext(Dispatchers.IO) {
        outcome {
            uploadRaw(bytes, fileName, contentType)
        }
    }

    private suspend fun uploadRaw(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): UploadResult {
        val response = httpClient.submitFormWithBinaryData(
            "$serverUrl/api/v1/files/upload",
            formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            },
        ) {
            accessToken?.let { headers { append(HttpHeaders.Authorization, "Bearer $it") } }
        }
        if (response.status != HttpStatusCode.OK) {
            throw AppError.Business(response.status.value, "Upload failed HTTP ${response.status.value}")
        }
        return FileOps.parseUploadResult(response.bodyAsText())
    }

    actual suspend fun download(path: String): Outcome<ByteArray> = FileOps.download(serverUrl, path)

    actual fun resolveUrl(path: String): String = FileOps.resolveUrl(serverUrl, path)
}
