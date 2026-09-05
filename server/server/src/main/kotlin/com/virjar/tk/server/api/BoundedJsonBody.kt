package com.virjar.tk.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

internal const val STRUCTURED_HTTP_BODY_MAX_BYTES = 1024 * 1024

@PublishedApi
internal val boundedRequestJson = Json

@PublishedApi
internal class StructuredHttpBodyTooLargeException : IllegalArgumentException()

/**
 * 通过精确的流式读取上界读取小型 JSON 契约。文件路由与结构化遥测路由
 * 各自持有独立的媒体特定限额，刻意不使用本辅助函数。
 */
internal suspend inline fun <reified T : Any> ApplicationCall.receiveBoundedJsonOrRespond(
    maxBytes: Int = STRUCTURED_HTTP_BODY_MAX_BYTES,
): T? {
    val bytes = try {
        receiveChannel().readStructuredBodyBounded(maxBytes)
    } catch (_: StructuredHttpBodyTooLargeException) {
        respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "request body too large"))
        return null
    }
    return try {
        boundedRequestJson.decodeFromString<T>(bytes.decodeToString())
    } catch (_: SerializationException) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid JSON request body"))
        null
    }
}

@PublishedApi
internal suspend fun ByteReadChannel.readStructuredBodyBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = readAvailable(buffer)
        if (count == -1) break
        if (count == 0) continue
        if (total > maxBytes - count) {
            cancel(CancellationException("Rejected oversized structured HTTP request body"))
            throw StructuredHttpBodyTooLargeException()
        }
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}
