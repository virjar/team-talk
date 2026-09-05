package com.virjar.tk.shared.repository

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.http.HttpConnectionOperationGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal actual fun createPlatformGroupBotHttpTransport(): PlatformGroupBotHttpTransport =
    DesktopGroupBotHttpTransport()

internal actual fun canonicalSecureGroupBotServerBase(serverUrl: String): String {
    val canonical = canonicalHttpServerBase(serverUrl)
    val parsed = URI(canonical)
    require(parsed.scheme == "https" || parsed.scheme == "http" && parsed.host.isExplicitLoopbackHost()) {
        "群机器人凭据管理必须使用 HTTPS（仅显式 loopback 可使用 HTTP）"
    }
    return canonical
}

private class DesktopGroupBotHttpTransport : PlatformGroupBotHttpTransport {
    private val operationGate = HttpConnectionOperationGate("Group bot HTTP transport")

    override suspend fun request(
        method: String,
        url: String,
        bearerToken: String,
        jsonBody: String?,
    ): String = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
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
            val operation = operationGate.register(connection) {
                IOException("Group bot HTTP transport is closed")
            }
            operation.executeSuspending {
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
            }
        } catch (error: IOException) {
            throw AppError.Network
        }
    }

    override fun close() {
        operationGate.close()
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
