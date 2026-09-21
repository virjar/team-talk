package com.virjar.tk.shared.client

import com.virjar.tk.shared.http.FoundationHttpClient
import com.virjar.tk.shared.http.foundationHttpRequest
import kotlinx.coroutines.runBlocking

internal actual fun createPlatformTelemetryHttpTransport(): PlatformTelemetryHttpTransport = FoundationTelemetryTransport()

private class FoundationTelemetryTransport : PlatformTelemetryHttpTransport {
    private val http = FoundationHttpClient()

    override fun postGzipJson(url: String, compressed: ByteArray, headers: Map<String, String>): PlatformTelemetryHttpResponse =
        runBlocking {
            http.operation {
                val request = foundationHttpRequest(url, "POST", headers + mapOf(
                    "Content-Type" to "application/json", "Content-Encoding" to "gzip",
                ), compressed)
                var status = 0
                try {
                    val (_, body) = readBounded(request, 64 * 1024) { response ->
                        status = response.status
                        if (status != 200) throw TelemetryStatusReceived()
                    }
                    PlatformTelemetryHttpResponse(status, body)
                } catch (_: TelemetryStatusReceived) {
                    PlatformTelemetryHttpResponse(status, null)
                }
            }
        }

    override fun close() = http.close()

    private class TelemetryStatusReceived : Exception()
}
