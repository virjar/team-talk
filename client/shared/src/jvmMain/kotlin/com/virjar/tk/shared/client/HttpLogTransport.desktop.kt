package com.virjar.tk.shared.client

import com.virjar.tk.shared.http.HttpConnectionOperationGate
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal actual fun createPlatformTelemetryHttpTransport(): PlatformTelemetryHttpTransport =
    DesktopTelemetryHttpTransport()

private class DesktopTelemetryHttpTransport : PlatformTelemetryHttpTransport {
    private val operationGate = HttpConnectionOperationGate("Telemetry HTTP transport")

    override fun postGzipJson(
        url: String,
        compressed: ByteArray,
        headers: Map<String, String>,
    ): PlatformTelemetryHttpResponse {
        val connection = openConnection(url, compressed, headers)
        val operation = operationGate.register(connection) {
            IOException("Telemetry HTTP transport is closed")
        }
        return operation.execute {
            connection.outputStream.use { it.write(compressed) }
            val status = connection.responseCode
            PlatformTelemetryHttpResponse(
                statusCode = status,
                body = if (status == 200) connection.inputStream.use(::readBoundedTelemetryResponse) else null,
            )
        }
    }

    private fun openConnection(
        url: String,
        compressed: ByteArray,
        headers: Map<String, String>,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        useCaches = false
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 30_000
        doOutput = true
        setFixedLengthStreamingMode(compressed.size)
        headers.forEach(::setRequestProperty)
        // 传输层拥有自己的线格式；调用方不能降级或矛盾地覆盖这些头部。
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Content-Encoding", "gzip")
    }

    override fun close() {
        operationGate.close()
    }
}

private fun readBoundedTelemetryResponse(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        require(output.size() + read <= MAX_TELEMETRY_RESPONSE_BYTES) {
            "Telemetry response is too large"
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray().decodeToString()
}

private const val MAX_TELEMETRY_RESPONSE_BYTES = 64 * 1024
