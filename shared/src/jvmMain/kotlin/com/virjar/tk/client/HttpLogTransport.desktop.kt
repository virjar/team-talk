package com.virjar.tk.client

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal actual fun createPlatformLogHttpTransport(): PlatformLogHttpTransport =
    DesktopLogHttpTransport()

private class DesktopLogHttpTransport : PlatformLogHttpTransport {
    private val lifecycleLock = Any()
    private val activeConnections = mutableSetOf<HttpURLConnection>()
    private var closed = false

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
        val admitted = synchronized(lifecycleLock) {
            if (closed) false else {
                activeConnections += connection
                true
            }
        }
        if (!admitted) {
            connection.disconnect()
            throw IOException("Log HTTP transport is closed")
        }
        return try {
            connection.outputStream.use { it.write(compressed) }
            connection.responseCode
        } finally {
            synchronized(lifecycleLock) { activeConnections -= connection }
            connection.disconnect()
        }
    }

    override fun close() {
        val connections = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            activeConnections.toList().also { activeConnections.clear() }
        }
        connections.forEach(HttpURLConnection::disconnect)
    }
}
