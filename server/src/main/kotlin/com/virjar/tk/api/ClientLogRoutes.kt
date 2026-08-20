package com.virjar.tk.api

import com.virjar.tk.domain.auth.AccessTokenValidator
import com.virjar.tk.infra.storage.ClientLogStore
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

private val logger = LoggerFactory.getLogger("ClientLogRoutes")

/**
 * 客户端日志 HTTP 接收端点。
 *
 * POST /api/client-logs
 * Content-Type: application/gzip（或 application/octet-stream）
 * Body: GZIP 压缩的日志文本（| 分隔的结构化行）
 *
 * Bearer access token is authoritative for both uid and device identity. The endpoint accepts only
 * bounded gzip payloads so diagnostics cannot become an anonymous storage or decompression sink.
 */
fun Route.clientLogRoutes(clientLogStore: ClientLogStore, accessTokens: AccessTokenValidator) {
    post("/api/client-logs") {
        val bearer = call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }
        val principal = bearer?.let { accessTokens.validateAccessToken(it) }
            ?: return@post call.respond(HttpStatusCode.Unauthorized, "invalid or missing token")

        val raw = try {
            call.receiveChannel().readBounded(CLIENT_LOG_MAX_COMPRESSED_BYTES)
        } catch (_: ClientLogPayloadTooLargeException) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, "client log payload too large")
        }
        val text = try {
            decodeClientLogPayload(raw)
        } catch (_: ClientLogPayloadTooLargeException) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, "client log payload too large")
        } catch (_: Exception) {
            return@post call.respond(HttpStatusCode.BadRequest, "invalid gzip client log payload")
        }

        clientLogStore.store(principal.uid, principal.deviceId, text)
        logger.info(
            "Client logs received: uid={}, deviceId={}, size={} chars",
            principal.uid,
            principal.deviceId,
            text.length,
        )

        // The upload contract only needs an acknowledgement. Returning a bare status keeps this
        // security-sensitive route independent from an application's optional JSON plugin.
        call.respond(HttpStatusCode.OK)
    }
}

internal const val CLIENT_LOG_MAX_COMPRESSED_BYTES = 1024 * 1024
internal const val CLIENT_LOG_MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024

private class ClientLogPayloadTooLargeException : IllegalArgumentException()

private suspend fun ByteReadChannel.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = readAvailable(buffer)
        if (count == -1) break
        total += count
        if (total > maxBytes) throw ClientLogPayloadTooLargeException()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun decodeClientLogPayload(
    compressed: ByteArray,
    maxBytes: Int = CLIENT_LOG_MAX_DECOMPRESSED_BYTES,
): String {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = gzip.read(buffer)
            if (count == -1) break
            total += count
            if (total > maxBytes) throw ClientLogPayloadTooLargeException()
            output.write(buffer, 0, count)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}
