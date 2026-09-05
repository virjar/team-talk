package com.virjar.tk.shared.agent

import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.shared.client.OutgoingMessageConflictException
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.shared.repository.UploadSource
import com.virjar.tk.shared.repository.asSmallUploadSource
import com.virjar.tk.shared.testkit.FakeLocalCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentOutgoingCoordinatorTest {

    @Test
    fun `concurrent sends for the same key upload and enqueue exactly once`() = runBlocking {
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val fixture = Fixture(
            files = mapOf("same.bin" to "same-content".encodeToByteArray()),
            beforeUpload = {
                uploadStarted.complete(Unit)
                releaseUpload.await()
            },
        )

        val receipts = coroutineScope {
            val first = async {
                fixture.coordinator.enqueueFile("chat-1", "client-1", "same.bin")
            }
            uploadStarted.await()
            val concurrentRetry = async {
                fixture.coordinator.enqueueFile("chat-1", "client-1", "same.bin")
            }
            yield()
            releaseUpload.complete(Unit)
            awaitAll(first, concurrentRetry)
        }

        assertEquals(1, fixture.uploadCalls.get())
        assertEquals(1, fixture.enqueueCalls.get())
        assertEquals(receipts.first().localOrdinal, receipts.last().localOrdinal)
    }

    @Test
    fun `retry of an existing receipt validates the snapshot without uploading again`() = runBlocking {
        val fixture = Fixture(mapOf("report.bin" to "durable-content".encodeToByteArray()))

        val admitted = fixture.coordinator.enqueueFile("chat-1", "client-1", "report.bin")
        val retried = fixture.coordinator.enqueueFile("chat-1", "client-1", "report.bin")

        assertEquals(admitted.localOrdinal, retried.localOrdinal)
        assertEquals(2, fixture.stageCalls.get(), "the retry must still validate its local snapshot")
        assertEquals(1, fixture.uploadCalls.get())
        assertEquals(1, fixture.enqueueCalls.get())
    }

    @Test
    fun `same key with a different file fingerprint fails closed before another upload`() = runBlocking {
        val fixture = Fixture(
            mapOf(
                "first/report.bin" to "first-content".encodeToByteArray(),
                "second/report.bin" to "different-content".encodeToByteArray(),
            ),
        )
        val admitted = fixture.coordinator.enqueueFile(
            "chat-1",
            "client-1",
            "first/report.bin",
        )

        assertFailsWith<OutgoingMessageConflictException> {
            fixture.coordinator.enqueueFile("chat-1", "client-1", "second/report.bin")
        }

        assertEquals(1, fixture.uploadCalls.get())
        assertEquals(1, fixture.enqueueCalls.get())
        assertEquals(
            admitted.localOrdinal,
            fixture.cache.getOutgoingMessage("chat-1", "client-1")?.localOrdinal,
        )
    }

    @Test
    fun `invalid file path is classified without exposing caller input`() = runBlocking {
        val secretPath = "/private/customer-name/secret.txt"
        val fixture = Fixture(emptyMap())

        val failure = assertFailsWith<AgentFileRequestException> {
            fixture.coordinator.enqueueFile("chat-1", "client-1", secretPath)
        }

        assertEquals("file path is not allowed", failure.message)
        assertFalse(secretPath in failure.message.orEmpty())
        assertEquals(0, fixture.uploadCalls.get())
        assertEquals(0, fixture.enqueueCalls.get())
    }

    @Test
    fun `ordinary staging cleanup failure is diagnostic and does not replace the admitted result`() = runBlocking {
        val cleanupFailure = IllegalStateException("cleanup failed")
        val fixture = Fixture(
            files = mapOf("report.bin" to "content".encodeToByteArray()),
            closeFailure = cleanupFailure,
        )

        val result = fixture.coordinator.enqueueFile("chat-1", "client-1", "report.bin")

        assertEquals("client-1", result.message.clientMsgId)
        assertEquals(1, fixture.enqueueCalls.get())
        assertEquals(listOf<Throwable>(cleanupFailure), fixture.cleanupFailures)
    }

    @Test
    fun `fatal staging cleanup keeps identity after the message was admitted`() = runBlocking {
        val fatal = AssertionError("fatal cleanup")
        val fixture = Fixture(
            files = mapOf("report.bin" to "content".encodeToByteArray()),
            closeFailure = fatal,
        )

        val observed = try {
            fixture.coordinator.enqueueFile("chat-1", "client-1", "report.bin")
            null
        } catch (failure: AssertionError) {
            failure
        }

        assertSame(fatal, observed)
        assertEquals(1, fixture.enqueueCalls.get())
        assertTrue(fixture.cleanupFailures.isEmpty())
    }

    @Test
    fun `operation cancellation keeps identity and suppresses ordinary staging cleanup`() = runBlocking {
        val cancellation = CancellationException("send cancelled")
        val cleanupFailure = IllegalStateException("cleanup failed")
        val fixture = Fixture(
            files = mapOf("report.bin" to "content".encodeToByteArray()),
            beforeUpload = { throw cancellation },
            closeFailure = cleanupFailure,
        )

        val observed = try {
            fixture.coordinator.enqueueFile("chat-1", "client-1", "report.bin")
            null
        } catch (failure: CancellationException) {
            failure
        }

        assertSame(cancellation, observed)
        assertTrue(cancellation.suppressed.any { it === cleanupFailure })
        assertTrue(fixture.cleanupFailures.isEmpty())
    }

    private class Fixture(
        private val files: Map<String, ByteArray>,
        private val beforeUpload: suspend () -> Unit = {},
        private val closeFailure: Throwable? = null,
    ) {
        val cache = FakeLocalCache()
        val stageCalls = AtomicInteger()
        val uploadCalls = AtomicInteger()
        val enqueueCalls = AtomicInteger()
        val cleanupFailures = mutableListOf<Throwable>()

        val coordinator = AgentFileSendCoordinator(
            findReceipt = cache::getOutgoingMessage,
            stage = { rawPath ->
                stageCalls.incrementAndGet()
                val bytes = checkNotNull(files[rawPath]) { "missing test file: $rawPath" }
                TestPreparedUpload(rawPath.substringAfterLast('/'), bytes, closeFailure)
            },
            upload = { prepared, contentType ->
                beforeUpload()
                val call = uploadCalls.incrementAndGet()
                Attachment(
                    path = "bot/upload-$call.bin",
                    name = prepared.originalFileName,
                    contentType = contentType,
                    size = prepared.source.contentLength,
                )
            },
            enqueue = { chatId, clientMsgId, attachment, fingerprint ->
                enqueueCalls.incrementAndGet()
                cache.enqueueOutgoingMessage(
                    message = Message(
                        chatId = chatId,
                        clientMsgId = clientMsgId,
                        senderUid = "bot-1",
                        messageType = MessageType.FILE.code,
                        timestamp = 1L,
                        body = FileBody(attachment),
                    ),
                    now = 2L,
                    requestFingerprint = fingerprint,
                )
            },
            reportCleanupFailure = { cleanupFailures += it },
        )
    }

    private class TestPreparedUpload(
        override val originalFileName: String,
        bytes: ByteArray,
        private val closeFailure: Throwable?,
    ) : AgentPreparedUpload {
        override val source: UploadSource = bytes.asSmallUploadSource()
        override val contentSha256: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        override fun close() {
            closeFailure?.let { throw it }
        }
    }
}
