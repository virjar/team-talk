package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.http.GroupBotCredentials
import com.virjar.tk.http.GroupBotSummary
import com.virjar.tk.outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

actual class HttpGroupBotManagementRepository actual constructor(
    serverUrl: String,
    accessToken: String?,
) : GroupBotManagementRepository {
    private val transport = AndroidGroupBotHttpTransport(serverUrl, accessToken)

    override suspend fun list(chatId: String): Outcome<List<GroupBotSummary>> = outcome {
        GroupBotHttpContract.decodeList(transport.request("GET", GroupBotHttpContract.listPath(chatId)))
    }

    override suspend fun create(chatId: String, name: String): Outcome<GroupBotCredentials> = outcome {
        GroupBotHttpContract.decodeCredentials(
            transport.request("POST", GroupBotHttpContract.listPath(chatId), GroupBotHttpContract.encodeCreate(name)),
        )
    }

    override suspend fun rotate(chatId: String, botId: String): Outcome<GroupBotCredentials> = outcome {
        GroupBotHttpContract.decodeCredentials(
            transport.request("POST", GroupBotHttpContract.rotatePath(chatId, botId)),
        )
    }

    override suspend fun remove(chatId: String, botId: String): Outcome<Unit> = outcome {
        transport.request("DELETE", GroupBotHttpContract.botPath(chatId, botId))
        Unit
    }
}

private class AndroidGroupBotHttpTransport(serverUrl: String, private val accessToken: String?) {
    private val baseUrl = serverUrl.trimEnd('/')

    suspend fun request(method: String, path: String, jsonBody: String? = null): String = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
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
                connection.disconnect()
            }
        } catch (error: IOException) {
            throw AppError.Network
        }
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
