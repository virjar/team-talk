package com.virjar.tk.shared.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 更新器的 HTTP 出口；下载进度是当前文件已经写入的字节数。 */
interface UpdateHttpClient {
    suspend fun get(url: String): ByteArray

    suspend fun download(url: String, target: File, expectedSize: Long, progress: (Long) -> Unit = {}) {
        val bytes = get(url)
        require(bytes.size.toLong() == expectedSize) { "下载文件大小不符" }
        target.writeBytes(bytes)
        progress(bytes.size.toLong())
    }
}

class UpdateHttpException(val statusCode: Int, message: String) : IOException(message)

internal object JdkUpdateHttpClient : UpdateHttpClient {
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private const val READ_TIMEOUT_MILLIS = 60_000L
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override suspend fun get(url: String): ByteArray = response(url) { input ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = interruptibleRead { input.read(buffer) }
            if (read < 0) break
            require(bytes.size() + read <= MAX_MANIFEST_BYTES) { "更新清单过大" }
            bytes.write(buffer, 0, read)
        }
        bytes.toByteArray()
    }

    override suspend fun download(url: String, target: File, expectedSize: Long, progress: (Long) -> Unit) =
        response(url) { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    val read = interruptibleRead { input.read(buffer) }
                    if (read < 0) break
                    written += read
                    require(written <= expectedSize) { "下载文件超过声明大小" }
                    output.write(buffer, 0, read)
                    progress(written)
                }
                require(written == expectedSize) { "下载文件大小不符" }
            }
        }

    private suspend fun <T> response(url: String, read: suspend (InputStream) -> T): T = withContext(Dispatchers.IO) {
        var body: InputStream? = null
        try {
            val response = interruptibleRead {
                client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                    // 在跨越可取消边界前接管响应，取消恰好发生在 headers 返回时也会关闭 body。
                    .also { body = it.body() }
            }
            if (response.statusCode() != 200) throw UpdateHttpException(response.statusCode(), "HTTP ${response.statusCode()} for $url")
            read(response.body())
        } finally {
            body?.close()
        }
    }

    /** JDK 流式响应的阻塞读取可被线程中断；每次读取独立计时，不限制整个大文件的下载时长。 */
    private suspend fun <T> interruptibleRead(block: () -> T): T = try {
        withTimeout(READ_TIMEOUT_MILLIS) { runInterruptible(Dispatchers.IO, block) }
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw SocketTimeoutException("更新服务响应超时").apply { initCause(cancelled) }
    } catch (failure: IOException) {
        currentCoroutineContext().ensureActive()
        throw failure
    }
}
