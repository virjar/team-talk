package com.virjar.tk.server.api

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class StrictSingleFileMultipartTest {
    @Test
    fun `missing content length and chunked bodies are rejected before parsing`() {
        val missing = assertFailsWith<UploadBodyRejection> {
            strictMultipartEnvelope(requestHeaders(contentLength = null), 1024)
        }
        assertEquals(HttpStatusCode.LengthRequired, missing.status)

        val chunked = assertFailsWith<UploadBodyRejection> {
            strictMultipartEnvelope(requestHeaders(contentLength = 10, transferEncoding = "chunked"), 1024)
        }
        assertEquals(HttpStatusCode.LengthRequired, chunked.status)
    }

    @Test
    fun `legal zero length utf8 filename and binary payloads stage exactly`() = runBlocking {
        val zero = stage(buildMultipart(filename = "零字节.bin", payload = byteArrayOf()))
        assertEquals("零字节.bin", zero.metadata.originalName)
        assertEquals(0, zero.metadata.payloadLength)
        assertEquals(sha256(byteArrayOf()), zero.metadata.payloadSha256)
        assertContentEquals(byteArrayOf(), zero.bytes)

        val binary = byteArrayOf(0, 1, 13, 10, -1, 0, 127) +
            "\r\n--$TEST_MULTIPART_BOUNDARY\r\nopaque".encodeToByteArray()
        val staged = stage(buildMultipart(filename = "二进制.bin", payload = binary))
        assertEquals("二进制.bin", staged.metadata.originalName)
        assertEquals("application/octet-stream", staged.metadata.contentType)
        assertEquals(sha256(binary), staged.metadata.payloadSha256)
        assertContentEquals(binary, staged.bytes)
    }

    @Test
    fun `reservation callback precedes target creation and exact body copy`() = runBlocking {
        val payload = byteArrayOf(9, 8, 7, 0, -1)
        val body = buildMultipart(filename = "ordered.bin", payload = payload)
        val directory = Files.createTempDirectory("strict-multipart-order-").toFile()
        val target = File(directory, "target.tmp")
        val events = mutableListOf<String>()
        try {
            val staged = ByteReadChannel(body).stageStrictSingleFileMultipart(
                StrictMultipartEnvelope(body.size.toLong(), TEST_MULTIPART_BOUNDARY),
                maxUploadBytes = 1024,
            ) { metadata ->
                events += "reserve:${metadata.payloadLength}"
                assertFalse(target.exists(), "target must not exist while storage reservation begins")
                assertEquals(payload.size.toLong(), metadata.payloadLength)
                check(target.createNewFile())
                events += "target"
                assertEquals(0L, target.length(), "payload copy must not start before callback returns")
                StrictMultipartStagingTarget(target, "reservation")
            }

            events += "copied"
            assertEquals(listOf("reserve:5", "target", "copied"), events)
            assertEquals("reservation", staged.owner)
            assertEquals(payload.size.toLong(), staged.payloadLength)
            assertEquals(sha256(payload), staged.payloadSha256)
            assertContentEquals(payload, target.readBytes())
        } finally {
            check(directory.deleteRecursively() || !directory.exists())
        }
    }

    @Test
    fun `form fields and oversized headers are rejected without consuming their bodies`(): Unit = runBlocking {
        val formBody = buildMultipart(
            disposition = "form-data; name=metadata",
            payload = byteArrayOf(1),
            includePartLength = false,
        )
        assertFailsWith<UploadBodyRejection> {
            stage(formBody, declaredLength = 512L * 1024L * 1024L)
        }

        val hugeHeaderBody = buildMultipart(
            extraHeader = "X".repeat(4 * 1024 + 1),
            payload = byteArrayOf(),
        )
        assertFailsWith<UploadBodyRejection> { stage(hugeHeaderBody) }
    }

    @Test
    fun `forged length and multipart epilogue are rejected`(): Unit = runBlocking {
        val body = buildMultipart(
            payload = byteArrayOf(1, 2, 3),
            partLengthOverride = 10,
        )
        assertFailsWith<UploadBodyRejection> { stage(body, declaredLength = body.size.toLong() + 7) }

        val epilogue = buildMultipart(payload = byteArrayOf(1), epilogue = ByteArray(32 * 1024) { 7 })
        assertFailsWith<UploadBodyRejection> { stage(epilogue) }
    }

    @Test
    fun `second part dialect is rejected when mandatory part content length is omitted`(): Unit = runBlocking {
        val firstPrefix = buildString {
            append("--$TEST_MULTIPART_BOUNDARY\r\n")
            append("Content-Disposition: form-data; name=file; filename=\"first.bin\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.encodeToByteArray()
        val secondPart = buildString {
            append("\r\n--$TEST_MULTIPART_BOUNDARY\r\n")
            append("Content-Disposition: form-data; name=file; filename=\"second.bin\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
            append("second")
            append("\r\n--$TEST_MULTIPART_BOUNDARY--\r\n")
        }.encodeToByteArray()

        assertFailsWith<UploadBodyRejection> { stage(firstPrefix + byteArrayOf(1) + secondPart) }
    }

    @Test
    fun `request body larger than payload plus fixed overhead is rejected in preflight`() {
        val rejection = assertFailsWith<UploadBodyRejection> {
            strictMultipartEnvelope(
                requestHeaders(contentLength = 1024 + MAX_MULTIPART_OVERHEAD_BYTES + 1),
                maxUploadBytes = 1024,
            )
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, rejection.status)
    }
}

private suspend fun stage(body: ByteArray, declaredLength: Long = body.size.toLong()): ParsedFixture {
    val target = Files.createTempFile("strict-multipart-test-", ".tmp").toFile()
    return try {
        val metadata = ByteReadChannel(body).stageStrictSingleFileMultipart(
            StrictMultipartEnvelope(declaredLength, TEST_MULTIPART_BOUNDARY),
            maxUploadBytes = 1024 * 1024,
        ) { StrictMultipartStagingTarget(target, Unit) }
        ParsedFixture(metadata, target.readBytes())
    } finally {
        check(target.delete() || !target.exists())
    }
}

private fun requestHeaders(
    contentLength: Long?,
    transferEncoding: String? = null,
): Headers = Headers.build {
    append(
        HttpHeaders.ContentType,
        "multipart/form-data; boundary=$TEST_MULTIPART_BOUNDARY",
    )
    contentLength?.let { append(HttpHeaders.ContentLength, it.toString()) }
    transferEncoding?.let { append(HttpHeaders.TransferEncoding, it) }
}

private fun buildMultipart(
    filename: String = "file.bin",
    disposition: String = "form-data; name=file; filename=\"$filename\"",
    payload: ByteArray,
    includePartLength: Boolean = true,
    partLengthOverride: Long? = null,
    extraHeader: String? = null,
    epilogue: ByteArray = byteArrayOf(),
): ByteArray {
    val prefix = buildString {
        append("--$TEST_MULTIPART_BOUNDARY\r\n")
        append("Content-Disposition: $disposition\r\n")
        append("Content-Type: application/octet-stream\r\n")
        if (includePartLength) append("Content-Length: ${partLengthOverride ?: payload.size.toLong()}\r\n")
        extraHeader?.let { append("X-Test: $it\r\n") }
        append("\r\n")
    }.encodeToByteArray()
    val suffix = "\r\n--$TEST_MULTIPART_BOUNDARY--\r\n".encodeToByteArray()
    return prefix + payload + suffix + epilogue
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private data class ParsedFixture(val metadata: StrictStagedFile<Unit>, val bytes: ByteArray)

private const val TEST_MULTIPART_BOUNDARY = "teamtalk-strict-test-boundary"
