package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.outcome
import io.ktor.client.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 端 [FileRepository] 实现 —— 上传用 Ktor [HttpClient]，
 * 下载与 URL 拼装复用 [FileOps]（两端逻辑一致）。
 */
actual class FileRepository actual constructor(private val serverUrl: String) {

    private val httpClient = HttpClient()

    actual suspend fun upload(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): Outcome<String> = withContext(Dispatchers.IO) {
        outcome {
            val response = httpClient.submitFormWithBinaryData(
                url = "$serverUrl/api/v1/files/upload",
                formData = formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, contentType)
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    })
                },
            )
            if (response.status != HttpStatusCode.OK) {
                throw AppError.Business(response.status.value, "Upload failed: ${response.status}")
            }
            FileOps.parseUploadPath(response.bodyAsText())
        }
    }

    actual suspend fun download(path: String): Outcome<ByteArray> = FileOps.download(serverUrl, path)

    actual fun resolveUrl(path: String): String = FileOps.resolveUrl(serverUrl, path)
}
