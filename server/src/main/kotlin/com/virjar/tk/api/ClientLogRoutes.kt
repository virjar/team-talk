package com.virjar.tk.api

import com.virjar.tk.infra.storage.ClientLogStore
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

private val logger = LoggerFactory.getLogger("ClientLogRoutes")

/**
 * 客户端日志 HTTP 接收端点。
 *
 * POST /api/client-logs
 * Content-Type: application/gzip（或 application/octet-stream）
 * Body: GZIP 压缩的日志文本（| 分隔的结构化行）
 *
 * TODO: 鉴权 — 当前无鉴权（与文件上传一致），后续补充设备签名或 token 验证
 */
fun Route.clientLogRoutes(clientLogStore: ClientLogStore) {
    post("/api/client-logs") {
        // TODO: 鉴权 — 从 header 提取设备签名或 token
        val deviceId = call.request.header("X-Device-Id") ?: "anonymous"

        val raw = call.receiveStream().readBytes()
        // 尝试 GZIP 解压，如果不是 GZIP 则当明文处理
        val text = try {
            GZIPInputStream(ByteArrayInputStream(raw)).bufferedReader().readText()
        } catch (_: Exception) {
            String(raw)
        }

        clientLogStore.store(deviceId, text)
        logger.info("Client logs received: deviceId={}, size={} chars", deviceId, text.length)

        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}
