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
import kotlinx.serialization.json.*

/**
 * Android 端 [FileRepository] 实现 —— 基于 Ktor [HttpClient] 做上传，[java.net.HttpURLConnection] 做下载。
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
            val body = response.bodyAsText()
            val path = Json.parseToJsonElement(body)
                .jsonObject["path"]?.jsonPrimitive?.content
                ?: throw AppError.Business(-1, "Invalid upload response: $body")
            path
        }
    }

    actual suspend fun download(path: String): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        outcome {
            val conn = (java.net.URL(resolveUrl(path)).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 120_000
            }
            val code = conn.responseCode
            if (code != 200) {
                throw AppError.Business(code, "Download failed HTTP $code")
            }
            conn.inputStream.readBytes()
        }
    }

    actual fun resolveUrl(path: String): String = "$serverUrl/api/v1/files/$path"
}
