package com.virjar.tk.repository

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.client.SessionHttpCredentials
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileRepositoryTransportTest {
    @Test
    fun `connection created after close is disconnected at registration barrier`() = runBlocking {
        val connection = TrackingConnection()
        val transport = UrlConnectionFileTransport { connection }
        transport.close()

        assertFailsWith<IllegalStateException> {
            transport.upload(
                url = "https://files.example/api/v1/files/upload",
                bearerToken = "token",
                plan = MultipartUploadPlan.create("a.bin", "application/octet-stream", 1),
                source = byteArrayOf(1).asSmallUploadSource(),
            )
        }
        assertEquals(1, connection.disconnectCount)
    }

    @Test
    fun `file source rejects same-length content changed after capture`() = runBlocking {
        val file = File.createTempFile("teamtalk-upload-source", ".bin")
        try {
            file.writeText("aaaa")
            val originalModified = file.lastModified()
            val source = file.asUploadSource()
            file.writeText("bbbb")
            assertTrue(file.setLastModified(originalModified + 2_000L))

            assertFailsWith<IllegalStateException> {
                source.writeTo(UploadSink { _, _, _ -> })
            }
        } finally {
            file.delete()
        }
        Unit
    }

    @Test
    fun `real transport uses fixed length chunks current token and sanitized multipart headers`() = runBlocking {
        withFileServer { fixture ->
            var credentials = SessionHttpCredentials("owner", "token-a")
            val repository = FileRepository(
                fixture.baseUrl,
                "owner",
                credentialsProvider = { credentials },
            )
            val source = GeneratedSource(8L * 1024 * 1024, 32 * 1024)
            try {
                repository.upload(
                    source,
                    "report\r\nX-Injected: yes\".bin",
                    "application/octet-stream\r\nX-Injected: yes",
                ).getOrThrow()
                credentials = SessionHttpCredentials("owner", "token-b")
                repository.uploadSmallBytes(byteArrayOf(7), "next.bin", "application/octet-stream").getOrThrow()

                assertEquals(
                    listOf<String?>("Bearer token-a", "Bearer token-b"),
                    fixture.authorizations,
                )
                assertEquals(source.contentLength, fixture.payloadBytes.first())
                assertEquals(32 * 1024, source.maxChunk)
                assertNull(fixture.transferEncodings.first())
                assertEquals(fixture.receivedRequestBytes.first(), fixture.declaredRequestBytes.first())
                assertTrue("\r\nX-Injected:" !in fixture.firstMultipartPrefix)
                assertTrue("filename=\"report__X-Injected: yes_.bin\"" in fixture.firstMultipartPrefix)
                assertTrue("Content-Type: application/octet-stream" in fixture.firstMultipartPrefix)
            } finally {
                repository.close()
            }
        }
    }

    @Test
    fun `authenticated upload never follows redirect and bounds oversized error response`() = runBlocking {
        withFileServer { fixture ->
            val repository = FileRepository(fixture.baseUrl, "owner") {
                SessionHttpCredentials("owner", "secret-token")
            }
            try {
                fixture.mode = ResponseMode.REDIRECT
                val redirect = repository.uploadSmallBytes(
                    byteArrayOf(1),
                    "redirect.bin",
                    "application/octet-stream",
                )
                assertIs<Outcome.Failure>(redirect)
                assertEquals(307, (redirect.error as AppError.Business).code)
                assertEquals(0, fixture.redirectTargetCalls.get())

                fixture.mode = ResponseMode.OVERSIZED_ERROR
                val oversized = repository.uploadSmallBytes(
                    byteArrayOf(2),
                    "error.bin",
                    "application/octet-stream",
                )
                assertIs<Outcome.Failure>(oversized)
                val error = assertIs<AppError.Business>(oversized.error)
                assertEquals(500, error.code)
                assertTrue("安全上限" in error.message)
            } finally {
                repository.close()
            }
        }
    }

    private suspend fun withFileServer(block: suspend (FileServerFixture) -> Unit) {
        val fixture = FileServerFixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class GeneratedSource(
        override val contentLength: Long,
        private val chunkSize: Int,
    ) : UploadSource {
        var maxChunk: Int = 0
            private set

        override suspend fun writeTo(sink: UploadSink) {
            val buffer = ByteArray(chunkSize) { (it % 251).toByte() }
            var remaining = contentLength
            while (remaining > 0L) {
                val length = minOf(remaining, buffer.size.toLong()).toInt()
                maxChunk = maxOf(maxChunk, length)
                sink.write(buffer, 0, length)
                remaining -= length
            }
        }
    }

    private class TrackingConnection : HttpURLConnection(URL("https://files.example/upload")) {
        var disconnectCount = 0

        override fun connect() = Unit
        override fun disconnect() {
            disconnectCount += 1
        }
        override fun usingProxy(): Boolean = false
    }

    private class FileServerFixture : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val authorizations = mutableListOf<String?>()
        val transferEncodings = mutableListOf<String?>()
        val declaredRequestBytes = mutableListOf<Long>()
        val receivedRequestBytes = mutableListOf<Long>()
        val payloadBytes = mutableListOf<Long>()
        val redirectTargetCalls = AtomicInteger()
        var firstMultipartPrefix: String = ""
        @Volatile var mode: ResponseMode = ResponseMode.SUCCESS

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/api/v1/files/upload", ::handleUpload)
            server.createContext("/redirected") { exchange ->
                redirectTargetCalls.incrementAndGet()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.close()
            }
            server.start()
        }

        private fun handleUpload(exchange: HttpExchange) {
            val prefix = ByteArrayOutputStream()
            val boundary = exchange.requestHeaders.getFirst("Content-Type").substringAfter("boundary=")
            val requestBytes = drainRequest(exchange, prefix)
            val prefixText = prefix.toByteArray().decodeToString()
            val headerEnd = prefixText.indexOf("\r\n\r\n")
            val multipartPrefixBytes = if (headerEnd >= 0) {
                prefixText.substring(0, headerEnd + 4).encodeToByteArray().size.toLong()
            } else {
                0L
            }
            val suffixBytes = "\r\n--$boundary--\r\n".encodeToByteArray().size.toLong()

            synchronized(this) {
                authorizations += exchange.requestHeaders.getFirst("Authorization")
                transferEncodings += exchange.requestHeaders.getFirst("Transfer-Encoding")
                declaredRequestBytes += exchange.requestHeaders.getFirst("Content-Length").toLong()
                receivedRequestBytes += requestBytes
                payloadBytes += requestBytes - multipartPrefixBytes - suffixBytes
                if (firstMultipartPrefix.isEmpty()) firstMultipartPrefix = prefixText.substringBefore("\r\n\r\n")
            }

            when (mode) {
                ResponseMode.SUCCESS -> exchange.respond(200, SUCCESS_RESPONSE.encodeToByteArray())
                ResponseMode.REDIRECT -> {
                    exchange.responseHeaders.add("Location", "$baseUrl/redirected")
                    exchange.respond(307, byteArrayOf())
                }
                ResponseMode.OVERSIZED_ERROR -> {
                    val total = 2L * 1024 * 1024
                    exchange.sendResponseHeaders(500, total)
                    runCatching {
                        exchange.responseBody.use { output ->
                            val chunk = ByteArray(16 * 1024) { 'x'.code.toByte() }
                            repeat((total / chunk.size).toInt()) { output.write(chunk) }
                        }
                    }
                }
            }
        }

        private fun drainRequest(exchange: HttpExchange, prefix: ByteArrayOutputStream): Long {
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            exchange.requestBody.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) return total
                    if (read == 0) continue
                    if (prefix.size() < PREFIX_CAPTURE_BYTES) {
                        val capture = minOf(read, PREFIX_CAPTURE_BYTES - prefix.size())
                        prefix.write(buffer, 0, capture)
                    }
                    total += read
                }
            }
        }

        private fun HttpExchange.respond(code: Int, body: ByteArray) {
            sendResponseHeaders(code, body.size.toLong())
            responseBody.use { it.write(body) }
        }

        override fun close() {
            server.stop(0)
        }

        private companion object {
            const val PREFIX_CAPTURE_BYTES = 2 * 1024
            const val SUCCESS_RESPONSE =
                "{\"file\":{\"path\":\"owner/file.bin\",\"name\":\"file.bin\"," +
                    "\"contentType\":\"application/octet-stream\",\"size\":1}}"
        }
    }

    private enum class ResponseMode { SUCCESS, REDIRECT, OVERSIZED_ERROR }
}
