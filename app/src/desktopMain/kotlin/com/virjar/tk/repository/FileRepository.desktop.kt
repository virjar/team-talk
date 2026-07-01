package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.outcome

/**
 * Desktop 端 [FileRepository] 实现 —— 上传用 [java.net.HttpURLConnection] 手搓 multipart，
 * 下载与 URL 拼装复用 [FileOps]（两端逻辑一致）。
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
            } catch (e: Exception) { System.err.println("[FileRepo] Failed to read error body: ${e.message}"); "" }
            throw AppError.Business(code, "Upload failed HTTP $code: $errorBody")
        }

        FileOps.parseUploadPath(conn.inputStream.bufferedReader().readText())
    }

    actual suspend fun download(path: String): Outcome<ByteArray> = FileOps.download(serverUrl, path)

    actual fun resolveUrl(path: String): String = FileOps.resolveUrl(serverUrl, path)
}
