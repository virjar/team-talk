package com.virjar.tk.client

import com.virjar.tk.http.HttpConnectionOperationGate
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal actual fun createPlatformLogHttpTransport(): PlatformLogHttpTransport =
    AndroidLogHttpTransport()

private class AndroidLogHttpTransport : PlatformLogHttpTransport {
    private val operationGate = HttpConnectionOperationGate("Log HTTP transport")

    override fun postGzip(url: String, compressed: ByteArray, headers: Map<String, String>): Int {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/gzip")
            setFixedLengthStreamingMode(compressed.size)
            headers.forEach(::setRequestProperty)
        }
        val operation = operationGate.register(connection) {
            IOException("Log HTTP transport is closed")
        }
        return operation.execute {
            connection.outputStream.use { it.write(compressed) }
            connection.responseCode
        }
    }

    override fun close() {
        operationGate.close()
    }
}
