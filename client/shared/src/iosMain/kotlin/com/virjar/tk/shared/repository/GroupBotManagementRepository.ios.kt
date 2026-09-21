package com.virjar.tk.shared.repository

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.http.FoundationHttpClient
import com.virjar.tk.shared.http.foundationHttpRequest

internal actual fun createPlatformGroupBotHttpTransport(): PlatformGroupBotHttpTransport = FoundationGroupBotTransport()

private class FoundationGroupBotTransport : PlatformGroupBotHttpTransport {
    private val http = FoundationHttpClient()

    override suspend fun request(method: String, url: String, bearerToken: String, jsonBody: String?): String =
        http.operation {
            val request = foundationHttpRequest(url, method, buildMap {
                put("Accept", "application/json")
                put("Authorization", "Bearer $bearerToken")
                if (jsonBody != null) put("Content-Type", "application/json; charset=utf-8")
            }, jsonBody?.encodeToByteArray())
            val (headers, body) = readBounded(request, GroupBotHttpContract.MAX_RESPONSE_BYTES) { headers ->
                if (headers.status == 401) throw AppError.AuthExpired
            }
            if (headers.status !in 200..299) throw AppError.Business(
                headers.status, GroupBotHttpContract.errorMessage(body, "机器人请求失败 HTTP ${headers.status}"),
            )
            body
        }

    override fun close() = http.close()
}
