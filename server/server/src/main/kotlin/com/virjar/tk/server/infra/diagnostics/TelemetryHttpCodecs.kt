package com.virjar.tk.server.infra.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** 解压后的载荷超出其有界预算；由路由层映射为对应的 HTTP 状态。 */
class TelemetryPayloadTooLargeException : IllegalArgumentException()

/** 遥测上传体的传输编解码：有界 gzip 解压、严格 UTF-8 解码与 SHA-256 摘要。 */

fun decompressTelemetryBody(compressed: ByteArray, maxDecompressedBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxDecompressedBytes, 8192))
    GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = gzip.read(buffer)
            if (count == -1) break
            total += count
            if (total > maxDecompressedBytes) throw TelemetryPayloadTooLargeException()
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

fun ByteArray.decodeUtf8Strict(): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this))
    .toString()

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val digits = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
