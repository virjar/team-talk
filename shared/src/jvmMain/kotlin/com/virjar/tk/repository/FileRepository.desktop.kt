package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.outcome
import com.virjar.tk.model.Attachment

/**
 * Desktop 端 [FileRepository] 实现 —— 上传用 [java.net.HttpURLConnection] 手搓 multipart，
 * 下载与 URL 拼装复用 [FileOps]（两端逻辑一致）。
 */
actual class FileRepository actual constructor(
    private val serverUrl: String,
    private val accessToken: String?,
) {

    actual suspend fun upload(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): Outcome<Attachment> = outcome {
        uploadRaw(bytes, fileName, contentType).file
    }

    actual suspend fun uploadWithMeta(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): Outcome<UploadResult> = uploadWithMeta(bytes, fileName, contentType) { }

    actual suspend fun uploadWithMeta(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
        onProgress: (Float) -> Unit,
    ): Outcome<UploadResult> = outcome {
        uploadRaw(bytes, fileName, contentType, onProgress)
    }

    private suspend fun uploadRaw(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
        onProgress: (Float) -> Unit = {},
    ): UploadResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val boundary = "----TeamTalkBoundary${System.currentTimeMillis()}"
        val conn = (java.net.URL("$serverUrl/api/v1/files/upload").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

        conn.outputStream.use { os ->
            os.write("--$boundary\r\n".toByteArray())
            os.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
            os.write("Content-Type: $contentType\r\n\r\n".toByteArray())
            // 分块写 + 进度回调（大文件上传动画数据源；128KB 粒度节流）
            val head = bytes.size
            val bufSize = 128 * 1024
            var sent = 0L
            var lastReport = 0L
            var off = 0
            while (off < head) {
                val n = minOf(bufSize, head - off)
                os.write(bytes, off, n)
                off += n
                sent += n
                if (sent - lastReport >= 128 * 1024 || off >= head) {
                    lastReport = sent
                    onProgress(sent.toFloat() / head)
                }
            }
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

        FileOps.parseUploadResult(conn.inputStream.bufferedReader().readText())
    }

    actual suspend fun download(attachment: Attachment): Outcome<ByteArray> = FileOps.download(serverUrl, attachment)

    actual fun resolveUrl(attachment: Attachment): String = FileOps.resolveUrl(serverUrl, attachment)
}
