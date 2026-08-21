package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.http.HttpConnectionOperationGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** A repeatable Android/JVM file source which never materializes the attachment in memory. */
fun File.asUploadSource(): UploadSource {
    val stableFile = canonicalFile
    require(stableFile.isFile) { "待上传文件不存在: ${stableFile.name}" }
    val expectedLength = stableFile.length()
    val expectedLastModified = stableFile.lastModified()
    return object : UploadSource {
        override val contentLength: Long = expectedLength

        override suspend fun writeTo(sink: UploadSink) = withContext(Dispatchers.IO) {
            check(
                stableFile.isFile &&
                    stableFile.length() == expectedLength &&
                    stableFile.lastModified() == expectedLastModified
            ) {
                "待上传文件在传输前发生变化: ${stableFile.name}"
            }
            var written = 0L
            stableFile.inputStream().buffered(DEFAULT_UPLOAD_CHUNK_BYTES).use { input ->
                val buffer = ByteArray(DEFAULT_UPLOAD_CHUNK_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    sink.write(buffer, 0, read)
                    written += read
                }
            }
            check(
                written == expectedLength &&
                    stableFile.length() == expectedLength &&
                    stableFile.lastModified() == expectedLastModified
            ) {
                "待上传文件在传输期间发生变化: ${stableFile.name}"
            }
        }
    }
}

internal actual fun createPlatformFileTransport(): PlatformFileTransport = AndroidUrlConnectionFileTransport()

internal actual fun canonicalHttpServerBase(serverUrl: String): String {
    val parsed = URI(serverUrl.trim())
    val scheme = parsed.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "文件服务器必须使用 HTTP(S)" }
    require(parsed.host != null) { "文件服务器地址缺少主机" }
    require(parsed.userInfo == null) { "文件服务器地址不能包含凭据" }
    require(parsed.rawQuery == null && parsed.rawFragment == null) {
        "文件服务器地址不能包含 query 或 fragment"
    }
    val port = when {
        parsed.port < 0 -> -1
        scheme == "http" && parsed.port == 80 -> -1
        scheme == "https" && parsed.port == 443 -> -1
        else -> parsed.port
    }
    val path = parsed.path.orEmpty().trimEnd('/').ifBlank { null }
    return URI(scheme, null, parsed.host.lowercase(), port, path, null, null)
        .toASCIIString()
        .trimEnd('/')
}

private class AndroidUrlConnectionFileTransport : PlatformFileTransport {
    private val operationGate = HttpConnectionOperationGate("File HTTP transport")

    override suspend fun upload(
        url: String,
        bearerToken: String,
        plan: MultipartUploadPlan,
        source: UploadSource,
    ): String = withContext(Dispatchers.IO) {
        val connection = open(url).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = UPLOAD_READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "multipart/form-data; boundary=${plan.boundary}")
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setFixedLengthStreamingMode(plan.contentLength)
        }
        withActiveConnection(connection) {
            BufferedOutputStream(connection.outputStream, DEFAULT_UPLOAD_CHUNK_BYTES).use { output ->
                output.write(plan.prefix)
                source.writeTo(UploadSink { bytes, offset, length -> output.write(bytes, offset, length) })
                output.write(plan.suffix)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = try {
                (stream?.use { readBounded(it, MAX_UPLOAD_RESPONSE_BYTES) } ?: byteArrayOf()).decodeToString()
            } catch (_: ResponseTooLargeException) {
                if (code in 200..299) throw AppError.Business(-1, "上传响应超过安全上限")
                throw AppError.Business(code, "上传失败（HTTP $code，错误响应超过安全上限）")
            }
            if (code !in 200..299) throw AppError.Business(code, "上传失败（HTTP $code）")
            response
        }
    }

    override suspend fun downloadSmall(
        url: String,
        bearerToken: String,
        maxBytes: Long,
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = open(url).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $bearerToken")
        }
        withActiveConnection(connection) {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.use { error -> runCatching { readBounded(error, MAX_ERROR_RESPONSE_BYTES) } }
                throw AppError.Business(code, "下载失败（HTTP $code）")
            }
            if (connection.contentLengthLong > maxBytes) {
                throw AppError.Business(-1, "下载响应超过内存安全上限")
            }
            try {
                connection.inputStream.use { readBounded(it, maxBytes) }
            } catch (_: ResponseTooLargeException) {
                throw AppError.Business(-1, "下载响应超过内存安全上限")
            }
        }
    }

    override fun close() {
        operationGate.close()
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        useCaches = false
    }

    private suspend fun <T> withActiveConnection(
        connection: HttpURLConnection,
        block: suspend () -> T,
    ): T {
        val operation = operationGate.register(connection) {
            IllegalStateException("文件传输已经关闭")
        }
        return operation.executeSuspending {
            val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) operation.abort()
            }
            try {
                try {
                    currentCoroutineContext().ensureActive()
                    block()
                } catch (failure: Throwable) {
                    currentCoroutineContext().ensureActive()
                    throw failure
                }
            } finally {
                cancellationHandle?.dispose()
            }
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        require(maxBytes in 1..Int.MAX_VALUE.toLong()) { "invalid response byte limit" }
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_UPLOAD_CHUNK_BYTES.toLong()).toInt())
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            if (read == 0) continue
            if (total > maxBytes - read) throw ResponseTooLargeException()
            output.write(buffer, 0, read)
            total += read
        }
    }

    private class ResponseTooLargeException : IllegalStateException()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val UPLOAD_READ_TIMEOUT_MS = 120_000
        const val DOWNLOAD_READ_TIMEOUT_MS = 120_000
        const val MAX_UPLOAD_RESPONSE_BYTES = 1024L * 1024
        const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024
    }
}
