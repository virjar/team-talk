package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.SessionHttpCredentials
import com.virjar.tk.client.UserSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileRepositoryTest {
    @Test
    fun `large repeatable source remains chunked and never needs one payload array`() = runBlocking {
        val transport = RecordingTransport()
        val source = GeneratedUploadSource(
            contentLength = 32L * 1024 * 1024,
            chunkBytes = 32 * 1024,
        )
        val repository = repository(transport)
        try {
            repository.upload(source, "large.bin", "application/octet-stream").getOrThrow()

            assertEquals(source.contentLength, transport.payloadBytes)
            assertEquals(32 * 1024, transport.maxChunkBytes)
            assertEquals(1024, transport.chunkCount)

            transport.resetPayloadCounters()
            repository.upload(source, "large-again.bin", "application/octet-stream").getOrThrow()
            assertEquals(source.contentLength, transport.payloadBytes, "同一 source 必须可重复打开")
        } finally {
            repository.close()
        }
    }

    @Test
    fun `repository captures a custom source length exactly once`() = runBlocking {
        var lengthReads = 0
        val source = object : UploadSource {
            override val contentLength: Long
                get() {
                    lengthReads += 1
                    return if (lengthReads == 1) 1L else Long.MAX_VALUE
                }

            override suspend fun writeTo(sink: UploadSink) {
                sink.write(byteArrayOf(1), 0, 1)
            }
        }
        val repository = repository(RecordingTransport())
        try {
            repository.upload(source, "dynamic.bin", "application/octet-stream").getOrThrow()
            assertEquals(1, lengthReads)
        } finally {
            repository.close()
        }
    }

    @Test
    fun `small byte convenience is explicit bounded and repeatable`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            ByteArray(MAX_SMALL_UPLOAD_BYTES + 1).asSmallUploadSource()
        }

        val original = byteArrayOf(1, 2, 3)
        val source = original.asSmallUploadSource()
        original.fill(9)
        val first = mutableListOf<Byte>()
        val second = mutableListOf<Byte>()
        source.writeTo(UploadSink { bytes, offset, length ->
            repeat(length) { first += bytes[offset + it] }
        })
        source.writeTo(UploadSink { bytes, offset, length ->
            repeat(length) { second += bytes[offset + it] }
        })

        assertEquals(listOf<Byte>(1, 2, 3), first)
        assertEquals(first, second)
    }

    @Test
    fun `same uid sees rotated token while another uid and close fail closed`() = runBlocking {
        val userSession = UserSession().apply {
            onAuthSuccess("owner", "owner", "Owner", "refresh-a", "token-a")
        }
        val transport = RecordingTransport()
        val repository = repository(transport, userSession::httpCredentialsSnapshot)

        repository.uploadSmallBytes(byteArrayOf(1), "a.bin", "application/octet-stream").getOrThrow()
        userSession.onAuthSuccess("owner", "owner", "Owner", "refresh-b", "token-b")
        repository.uploadSmallBytes(byteArrayOf(2), "b.bin", "application/octet-stream").getOrThrow()
        assertEquals(listOf("token-a", "token-b"), transport.tokens)

        userSession.onAuthSuccess("other", "other", "Other", "other-refresh", "other-token")
        assertIs<Outcome.Failure>(
            repository.uploadSmallBytes(byteArrayOf(3), "c.bin", "application/octet-stream"),
        )
        assertEquals(listOf("token-a", "token-b"), transport.tokens)

        repository.close()
        repository.close()
        assertEquals(1, transport.closeCount)
        assertIs<Outcome.Failure>(
            repository.uploadSmallBytes(byteArrayOf(4), "d.bin", "application/octet-stream"),
        )
        Unit
    }

    @Test
    fun `close between chunk write and progress publication suppresses late callback`() = runBlocking {
        lateinit var repository: FileRepository
        val transport = RecordingTransport(onChunk = { repository.close() })
        repository = repository(transport)
        var callbacks = 0

        val outcome = repository.uploadWithMeta(
            byteArrayOf(1).asSmallUploadSource(),
            "late.bin",
            "application/octet-stream",
        ) { callbacks += 1 }

        assertIs<Outcome.Failure>(outcome)
        assertEquals(0, callbacks)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `cancellation prevents a transport unwinding with a late progress callback`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = object : PlatformFileTransport {
            override suspend fun upload(
                url: String,
                bearerToken: String,
                plan: MultipartUploadPlan,
                source: UploadSource,
            ): String {
                entered.complete(Unit)
                try {
                    release.await()
                } finally {
                    source.writeTo(UploadSink { _, _, _ -> })
                }
                return SUCCESS_RESPONSE
            }

            override suspend fun downloadSmall(url: String, bearerToken: String, maxBytes: Long) = byteArrayOf()
            override fun close() = Unit
        }
        val repository = repository(transport)
        var callbacks = 0
        val upload = async {
            repository.uploadWithMeta(
                byteArrayOf(1).asSmallUploadSource(),
                "cancel.bin",
                "application/octet-stream",
            ) { callbacks += 1 }
        }
        entered.await()
        upload.cancel()
        release.complete(Unit)

        assertFailsWith<kotlinx.coroutines.CancellationException> { upload.await() }
        assertEquals(0, callbacks)
        repository.close()
    }

    @Test
    fun `multipart rules remove header injection and reject malformed content type`() {
        val plan = MultipartUploadPlan.create(
            fileName = "report\r\nX-Evil: yes\".txt",
            contentType = "text/plain\r\nX-Evil: yes",
            fileLength = 12,
        )
        val header = plan.prefix.decodeToString()

        assertTrue("\r\nX-Evil:" !in header)
        assertTrue("filename=\"report__X-Evil: yes_.txt\"" in header)
        assertTrue("Content-Type: application/octet-stream" in header)
        assertEquals(plan.prefix.size.toLong() + 12L + plan.suffix.size, plan.contentLength)
    }

    private fun repository(
        transport: PlatformFileTransport,
        credentials: () -> SessionHttpCredentials = {
            SessionHttpCredentials("owner", "token")
        },
    ) = FileRepository(
        serverUrl = "https://files.example",
        ownerUid = "owner",
        credentialsProvider = credentials,
        transport = transport,
    )

    private class GeneratedUploadSource(
        override val contentLength: Long,
        private val chunkBytes: Int,
    ) : UploadSource {
        override suspend fun writeTo(sink: UploadSink) {
            val reusableBuffer = ByteArray(chunkBytes) { (it % 251).toByte() }
            var remaining = contentLength
            while (remaining > 0L) {
                val length = minOf(remaining, reusableBuffer.size.toLong()).toInt()
                sink.write(reusableBuffer, 0, length)
                remaining -= length
            }
        }
    }

    private class RecordingTransport(
        private val onChunk: () -> Unit = {},
    ) : PlatformFileTransport {
        val tokens = mutableListOf<String>()
        var payloadBytes = 0L
        var maxChunkBytes = 0
        var chunkCount = 0
        var closeCount = 0

        override suspend fun upload(
            url: String,
            bearerToken: String,
            plan: MultipartUploadPlan,
            source: UploadSource,
        ): String {
            tokens += bearerToken
            source.writeTo(UploadSink { _, _, length ->
                payloadBytes += length
                maxChunkBytes = maxOf(maxChunkBytes, length)
                chunkCount += 1
                onChunk()
            })
            return SUCCESS_RESPONSE
        }

        override suspend fun downloadSmall(url: String, bearerToken: String, maxBytes: Long) = byteArrayOf()

        override fun close() {
            closeCount += 1
        }

        fun resetPayloadCounters() {
            payloadBytes = 0L
            maxChunkBytes = 0
            chunkCount = 0
        }
    }

    private companion object {
        const val SUCCESS_RESPONSE =
            "{\"file\":{\"path\":\"owner/file.bin\",\"name\":\"file.bin\"," +
                "\"contentType\":\"application/octet-stream\",\"size\":1}}"
    }
}
