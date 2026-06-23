package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.outcome
import kotlinx.serialization.json.*

/**
 * Desktop 端 [FileRepository] 实现 —— 基于 [java.net.HttpURLConnection]。
 */
actual class FileRepository actual constructor(private val serverUrl: String) {

    actual suspend fun upload(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): Outcome<String> = outcome {
        val boundary = "----TeamTalkBoundary${System.currentTimeMillis()}"
        val conn = (java.net.URL("$serverUrl/api/v1/files/upload").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        conn.outputStream.use { os ->
            os.write("--$boundary\r\n".toByteArray())
            os.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
            os.write("Content-Type: $contentType\r\n\r\n".toByteArray())
            os.write(bytes)
            os.write("\r\n".toByteArray())
            os.write("--$boundary--\r\n".toByteArray())
        }

        val code = conn.responseCode
        if (code != 200) {
            val errorBody = try {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            } catch (_: Exception) { "" }
            throw AppError.Business(code, "Upload failed HTTP $code: $errorBody")
        }

        val body = conn.inputStream.bufferedReader().readText()
        val path = Json.parseToJsonElement(body)
            .jsonObject["path"]?.jsonPrimitive?.content
            ?: throw AppError.Business(-1, "Invalid upload response: $body")
        path
    }

    actual suspend fun download(path: String): Outcome<ByteArray> = outcome {
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

    actual fun resolveUrl(path: String): String = "$serverUrl/api/v1/files/$path"
}
