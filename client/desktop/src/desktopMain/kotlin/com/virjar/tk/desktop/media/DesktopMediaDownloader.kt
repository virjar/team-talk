package com.virjar.tk.desktop.media

import com.virjar.tk.shared.AppError
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.shared.http.HttpConnectionOperationGate
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

private sealed interface DesktopMediaWaitSelection<out T> {
    data class WaiterFailure(val error: Throwable) : DesktopMediaWaitSelection<Nothing>
    data class Flight<T>(val value: T) : DesktopMediaWaitSelection<T>
}

/**
 * 在共享 flight 与本等待者私有的回调失败之间做选择，然后重新检查调用方取消状态，
 * 即使已完成的子句走了 select 的非挂起快速路径也一样。
 */
internal suspend fun <T> awaitDesktopMediaFlight(
    flight: Deferred<T>,
    waiterFailure: Deferred<Throwable>,
): T {
    val selection = select<DesktopMediaWaitSelection<T>> {
        waiterFailure.onAwait { DesktopMediaWaitSelection.WaiterFailure(it) }
        flight.onAwait { DesktopMediaWaitSelection.Flight(it) }
    }
    coroutineContext.ensureActive()
    return when (selection) {
        is DesktopMediaWaitSelection.WaiterFailure -> throw selection.error
        is DesktopMediaWaitSelection.Flight -> selection.value
    }
}

internal data class DesktopMediaDownloadRequest(
    val resolvedUrl: String,
    val authorizationToken: String,
    val expectedBytes: Long,
)

internal fun desktopMediaDownloadHttpFailure(status: Int): AppError = when (status) {
    HttpURLConnection.HTTP_UNAUTHORIZED -> AppError.AuthExpired
    else -> AppError.Business(status, "下载失败（HTTP $status）")
}

internal fun interface DesktopMediaDownloader : Closeable {
    suspend fun download(
        request: DesktopMediaDownloadRequest,
        partialFile: File,
        onProgress: (Float) -> Unit,
    ): Long

    override fun close() = Unit
}

/** 不跟随重定向，避免认证 header 被带到其他主机。 */
internal class HttpDesktopMediaDownloader(
    private val connectionFactory: (String) -> HttpURLConnection = { raw ->
        URL(raw).openConnection() as HttpURLConnection
    },
    private val beforeFirstIo: () -> Unit = {},
) : DesktopMediaDownloader {
    private val operationGate = HttpConnectionOperationGate("Desktop media downloader")

    override suspend fun download(
        request: DesktopMediaDownloadRequest,
        partialFile: File,
        onProgress: (Float) -> Unit,
    ): Long {
        validateDesktopMediaDownloadSize(request.expectedBytes, AttachmentPolicy.MAX_UPLOAD_BYTES)
        val connection = connectionFactory(request.resolvedUrl).apply {
            connectTimeout = 10_000
            readTimeout = 120_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer ${request.authorizationToken}")
        }
        val operation = operationGate.register(connection) {
            IllegalStateException("Desktop 媒体下载器已经关闭")
        }
        return operation.executeSuspending(beforeFirstIoAdmission = beforeFirstIo) {
            connection.connect()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw desktopMediaDownloadHttpFailure(code)
            val total = connection.contentLengthLong
            validateDesktopMediaResponseLength(total, request.expectedBytes)
            var downloaded = 0L
            var lastReported = 0L
            connection.inputStream.use { input ->
                partialFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        coroutineContext.ensureActive()
                        if (
                            downloaded > request.expectedBytes - count ||
                            downloaded > AttachmentPolicy.MAX_UPLOAD_BYTES - count
                        ) {
                            throw DesktopMediaDownloadSizeException("下载响应超过附件声明大小")
                        }
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = downloaded
                            onProgress(progress(downloaded, request.expectedBytes))
                        }
                    }
                }
            }
            if (downloaded != request.expectedBytes) {
                throw DesktopMediaDownloadSizeException("下载响应大小与附件元数据不一致")
            }
            downloaded
        }
    }

    override fun close() = operationGate.close()

    private fun progress(downloaded: Long, total: Long): Float =
        if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else -1f

    private companion object {
        const val PROGRESS_STEP_BYTES = 128L * 1024L
    }
}
