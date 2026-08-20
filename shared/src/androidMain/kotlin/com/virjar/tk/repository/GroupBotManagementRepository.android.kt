package com.virjar.tk.repository

import com.virjar.tk.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal actual fun createPlatformGroupBotHttpTransport(): PlatformGroupBotHttpTransport =
    AndroidGroupBotHttpTransport()

private class AndroidGroupBotHttpTransport : PlatformGroupBotHttpTransport {
    private val lifecycleLock = Any()
    private val activeConnections = mutableSetOf<HttpURLConnection>()
    private var closed = false

    override suspend fun request(
        method: String,
        url: String,
        bearerToken: String,
        jsonBody: String?,
    ): String = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $bearerToken")
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            val accepted = synchronized(lifecycleLock) {
                if (closed) false else {
                    activeConnections += connection
                    true
                }
            }
            if (!accepted) {
                connection.disconnect()
                throw IOException("Group bot HTTP transport is closed")
            }
            try {
                jsonBody?.encodeToByteArray()?.let { bytes -> connection.outputStream.use { it.write(bytes) } }
                val status = connection.responseCode
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use(::readGroupBotResponse)
                    .orEmpty()
                when {
                    status == 401 -> throw AppError.AuthExpired
                    status !in 200..299 -> throw AppError.Business(
                        status,
                        GroupBotHttpContract.errorMessage(body, "机器人请求失败 HTTP $status"),
                    )
                    else -> body
                }
            } finally {
                synchronized(lifecycleLock) { activeConnections -= connection }
                connection.disconnect()
            }
        } catch (error: IOException) {
            throw AppError.Network
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

private fun readGroupBotResponse(input: java.io.InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (output.size() + read > GroupBotHttpContract.MAX_RESPONSE_BYTES) {
            throw AppError.Business(413, "机器人响应过大")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray().decodeToString()
}
