package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.shared.testkit.FakeLocalCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SendQueueTest {
    @Test
    fun `outgoing failure codes have unique stable storage and API identities`() {
        assertEquals(
            OutgoingFailureCode.entries.size,
            OutgoingFailureCode.entries.map(OutgoingFailureCode::storageCode).toSet().size,
        )
        assertEquals(
            OutgoingFailureCode.entries.size,
            OutgoingFailureCode.entries.map(OutgoingFailureCode::apiCode).toSet().size,
        )
        OutgoingFailureCode.entries.forEach { code ->
            assertEquals(code, OutgoingFailureCode.fromStorageCode(code.storageCode))
            assertTrue(code.publicMessage.isNotBlank())
        }
        assertEquals(
            setOf(
                OutgoingFailureCode.RATE_LIMITED,
                OutgoingFailureCode.SERVER_UNAVAILABLE,
                OutgoingFailureCode.AUTHENTICATION_REQUIRED,
                OutgoingFailureCode.REMOTE_REJECTED,
                OutgoingFailureCode.CLIENT_VALIDATION,
            ),
            OutgoingFailureCode.entries.filterTo(mutableSetOf()) {
                it.allowsFreshClientMsgIdReplacement
            },
        )
    }

    @Test
    fun `ack classification keeps only ambiguous and transient responses retryable`() {
        assertTrue(
            outgoingAckFailureCode(MessageAckPayload("c1", "m1", 0L, -1, "ACK timeout"), "c1", "m1")!!
                .retriesAutomatically,
        )
        listOf(429, 500, 503, 599).forEach { code ->
            assertTrue(
                outgoingAckFailureCode(
                    MessageAckPayload("c1", "m1", 0L, code, "server unavailable"),
                    "c1",
                    "m1",
                )!!.retriesAutomatically,
            )
        }
        assertNull(outgoingAckFailureCode(MessageAckPayload("c1", "m1", 9L, 0), "c1", "m1"))
        listOf(
            MessageAckPayload("c1", "m1", 0L, 400, "rejected"),
            MessageAckPayload("c1", "m1", 0L, 401, "expired"),
            MessageAckPayload("c1", "m1", 0L, 409, "conflict"),
            MessageAckPayload("c1", "m1", 0L, 499, "rejected"),
            MessageAckPayload("c1", "other", 9L, 0, null),
            MessageAckPayload("other-chat", "m1", 9L, 0, null),
            MessageAckPayload("c1", "m1", 0L, 0, null),
        ).forEach { ack ->
            assertFalse(outgoingAckFailureCode(ack, "c1", "m1")!!.retriesAutomatically)
        }
        val expectedCodes = listOf(
            MessageAckPayload("c1", "m1", 0L, -1) to OutgoingFailureCode.ACK_TIMEOUT,
            MessageAckPayload("c1", "m1", 0L, -2) to OutgoingFailureCode.TRANSPORT_UNAVAILABLE,
            MessageAckPayload("c1", "m1", 0L, 429) to OutgoingFailureCode.RATE_LIMITED,
            MessageAckPayload("c1", "m1", 0L, 503) to OutgoingFailureCode.SERVER_UNAVAILABLE,
            MessageAckPayload("c1", "m1", 0L, 401) to OutgoingFailureCode.AUTHENTICATION_REQUIRED,
            MessageAckPayload("c1", "m1", 0L, 400) to OutgoingFailureCode.REMOTE_REJECTED,
            MessageAckPayload("c1", "other", 9L, 0) to OutgoingFailureCode.ACK_IDENTITY_MISMATCH,
            MessageAckPayload("other-chat", "m1", 9L, 0) to OutgoingFailureCode.ACK_IDENTITY_MISMATCH,
            MessageAckPayload("c1", "m1", 0L, 0) to OutgoingFailureCode.INVALID_ACK,
        )
        expectedCodes.forEach { (ack, expected) ->
            assertEquals(expected, outgoingAckFailureCode(ack, "c1", "m1"))
        }
        assertNull(outgoingAckFailureCode(MessageAckPayload("c1", "m1", 9L, 0), "c1", "m1"))
    }

    @Test
    fun `transport loss keeps the head item and retries after authentication`() = runTest {
        val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
        val sent = CompletableDeferred<Unit>()
        val failed = mutableListOf<OutgoingFailureCode>()
        var attempts = 0
        val cache = FakeLocalCache()
        val queue = SendQueue(
            ownerUid = "user-1",
            localCache = cache,
            connectionState = state,
            sender = MessageSender { message ->
                attempts += 1
                if (attempts == 1) {
                    state.value = ConnectionState.DISCONNECTED
                    throw TransportUnavailableException("Connection closed before message ACK")
                }
                com.virjar.tk.protocol.payload.MessageAckPayload(
                    message.chatId, message.clientMsgId, 9L, 0,
                )
            },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            onSent = { _, _ -> sent.complete(Unit) },
            onFailed = { _, reason -> failed += reason },
        )

        queue.enqueue(message("chat-retry", "client-retry", "user-1"))
        runCurrent()
        assertEquals(1, attempts)

        state.value = ConnectionState.AUTHENTICATED
        assertEquals(Unit, withTimeout(1_000) { sent.await() })

        assertEquals(2, attempts)
        assertEquals(emptyList(), failed)
        queue.close()
    }

    @Test
    fun `lost successful response retries the same durable client id`() = runTest {
        val sentIds = mutableListOf<String>()
        val completed = CompletableDeferred<Unit>()
        val cache = FakeLocalCache()
        var attempts = 0
        val queue = SendQueue(
            ownerUid = "user-1",
            localCache = cache,
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            sender = MessageSender { message ->
                attempts += 1
                sentIds += message.clientMsgId
                if (attempts == 1) {
                    com.virjar.tk.protocol.payload.MessageAckPayload(
                        message.chatId,
                        message.clientMsgId,
                        serverSeq = 0L,
                        code = -1,
                        reason = "ACK timeout",
                    )
                } else {
                    com.virjar.tk.protocol.payload.MessageAckPayload(
                        message.chatId, message.clientMsgId, 9L, 0,
                    )
                }
            },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            onSent = { _, _ -> completed.complete(Unit) },
        )

        queue.enqueue(message("chat-response-lost", "stable-client-id", "user-1"))
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(OutgoingMessageState.RETRY_WAIT, cache.recoverOutgoingMessages(0L).single().state)

        advanceTimeBy(500L)
        runCurrent()
        assertEquals(Unit, withTimeout(1_000) { completed.await() })
        assertEquals(listOf("stable-client-id", "stable-client-id"), sentIds)
        val receipt = cache.recoverOutgoingMessages(testScheduler.currentTime).single()
        assertEquals(OutgoingMessageState.SUCCESS, receipt.state)
        assertEquals(9L, receipt.serverSeq)
        queue.close()
    }

    @Test
    fun `SDK validation failure is reported instead of disguised as timeout`() = runTest {
        val failure = CompletableDeferred<OutgoingFailureCode>()
        val cache = FakeLocalCache()
        val queue = SendQueue(
            ownerUid = "user-1",
            localCache = cache,
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            sender = MessageSender { throw IllegalArgumentException("附件路径非法") },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            onFailed = { _, reason -> failure.complete(reason) },
        )

        queue.enqueue(message("chat-1", "client-1", "user-1"))

        assertEquals(OutgoingFailureCode.CLIENT_VALIDATION, withTimeout(1_000) { failure.await() })
        assertEquals(
            OutgoingFailureCode.CLIENT_VALIDATION,
            cache.getOutgoingMessage("chat-1", "client-1")?.failureCode,
        )
        queue.close()
    }

    @Test
    fun `terminal ACK stores bounded private detail but callback exposes only stable code`() = runTest {
        val privateDetail = "/private/customer/token=" + "x".repeat(1_200)
        val callback = CompletableDeferred<OutgoingFailureCode>()
        val cache = FakeLocalCache()
        val queue = SendQueue(
            ownerUid = "user-1",
            localCache = cache,
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            sender = MessageSender { message ->
                MessageAckPayload(message.chatId, message.clientMsgId, 0L, 400, privateDetail)
            },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            onFailed = { _, code -> callback.complete(code) },
        )

        queue.enqueue(message("private", "private-id", "user-1"))

        assertEquals(OutgoingFailureCode.REMOTE_REJECTED, withTimeout(1_000) { callback.await() })
        val receipt = cache.getOutgoingMessage("private", "private-id")!!
        assertEquals(OutgoingFailureCode.REMOTE_REJECTED, receipt.failureCode)
        assertFalse(privateDetail in receipt.toString())
        queue.close()
    }

    @Test
    fun `unexpected sender exception is classified and remains retryable`() = runTest {
        val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
        val cache = FakeLocalCache()
        val queue = SendQueue(
            ownerUid = "user-1",
            localCache = cache,
            connectionState = state,
            sender = MessageSender {
                state.value = ConnectionState.DISCONNECTED
                throw IllegalStateException("private unexpected detail")
            },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )

        queue.enqueue(message("unexpected", "unexpected-id", "user-1"))
        runCurrent()

        val receipt = cache.getOutgoingMessage("unexpected", "unexpected-id")!!
        assertEquals(OutgoingMessageState.RETRY_WAIT, receipt.state)
        assertEquals(OutgoingFailureCode.UNEXPECTED_FAILURE, receipt.failureCode)
        assertFalse("private unexpected detail" in receipt.toString())
        queue.close()
    }

    @Test
    fun `worker failure preserves durable work and rejects admission without a sender`() = runTest {
        val cache = FakeLocalCache()
        val workerFailure = IllegalStateException("queue observer failed")
        val observedFailures = mutableListOf<Throwable>()
        val queue = SendQueue(
            ownerUid = "user-1",
            localCache = cache,
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            sender = MessageSender { message ->
                MessageAckPayload(message.chatId, message.clientMsgId, 9L, 0)
            },
            scope = CoroutineScope(
                backgroundScope.coroutineContext + CoroutineExceptionHandler { _, failure ->
                    observedFailures += failure
                },
            ),
            clock = { testScheduler.currentTime },
            onSent = { _, _ -> throw workerFailure },
        )
        queue.enqueue(message("worker-failure", "sent", "user-1"))
        queue.enqueue(message("worker-failure", "pending", "user-1"))

        runCurrent()

        assertSame(workerFailure, observedFailures.single())
        assertEquals(
            OutgoingMessageState.SUCCESS,
            cache.getOutgoingMessage("worker-failure", "sent")?.state,
        )
        assertEquals(
            OutgoingMessageState.PENDING,
            cache.getOutgoingMessage("worker-failure", "pending")?.state,
        )
        assertFailsWith<IllegalStateException> {
            queue.enqueue(message("worker-failure", "unowned", "user-1"))
        }
        assertNull(cache.getOutgoingMessage("worker-failure", "unowned"))
        // worker 故障没有消费显式登出；CANCEL 仍必须退休此前已准入的未完成消息。
        queue.close(SendQueueCloseDisposition.CANCEL)
        assertEquals(
            OutgoingMessageState.TERMINAL_FAILED,
            cache.getOutgoingMessage("worker-failure", "pending")?.state,
        )
    }

    @Test
    fun `ack returning after close cannot publish sent or failed callback`() = runTest {
        val senderEntered = CompletableDeferred<Unit>()
        val releaseAck = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<String>()
        val queue = SendQueue(
            ownerUid = "u1",
            localCache = FakeLocalCache(),
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            sender = MessageSender { message ->
                senderEntered.complete(Unit)
                releaseAck.await()
                com.virjar.tk.protocol.payload.MessageAckPayload(
                    message.chatId, message.clientMsgId, 11L, 0,
                )
            },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            onSent = { _, _ -> callbacks += "sent" },
            onFailed = { _, _ -> callbacks += "failed" },
        )

        queue.enqueue(message("retired", "late-ack", "u1"))
        runCurrent()
        assertTrue(senderEntered.isCompleted)

        queue.close()
        releaseAck.complete(Unit)
        runCurrent()

        assertEquals(emptyList(), callbacks)
    }

    @Test
    fun `preserve keeps account work while cancel terminally retires it`() = runTest {
        val preservedCache = FakeLocalCache()
        val preserved = SendQueue(
            ownerUid = "u1",
            localCache = preservedCache,
            connectionState = MutableStateFlow(ConnectionState.DISCONNECTED),
            sender = MessageSender { error("must stay offline") },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        preserved.enqueue(message("c1", "preserved", "u1"))
        preserved.close(SendQueueCloseDisposition.PRESERVE)
        assertEquals(OutgoingMessageState.PENDING, preservedCache.recoverOutgoingMessages(1L).single().state)

        val cancelledCache = FakeLocalCache()
        val cancelled = SendQueue(
            ownerUid = "u1",
            localCache = cancelledCache,
            connectionState = MutableStateFlow(ConnectionState.DISCONNECTED),
            sender = MessageSender { error("must stay offline") },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        cancelled.enqueue(message("c1", "cancelled", "u1"))
        cancelled.close(SendQueueCloseDisposition.CANCEL)
        assertEquals(
            OutgoingMessageState.TERMINAL_FAILED,
            cancelledCache.recoverOutgoingMessages(1L).single().state,
        )
    }

    @Test
    fun `fixed queue owner rejects another account before persistence`() = runTest {
        val cache = FakeLocalCache()
        val queue = SendQueue(
            ownerUid = "u1",
            localCache = cache,
            connectionState = MutableStateFlow(ConnectionState.DISCONNECTED),
            sender = MessageSender { error("must stay offline") },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )

        assertFailsWith<IllegalArgumentException> {
            queue.enqueue(message("c1", "wrong-owner", "u2"))
        }
        assertTrue(cache.recoverOutgoingMessages(1L).isEmpty())
        queue.close()
    }

    @Test
    fun `queue snapshot flow follows admission and terminal recovery without payload reads`() = runTest {
        val cache = FakeLocalCache()
        val failed = cache.enqueueOutgoingMessage(message("snapshot", "failed", "user-1"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        cache.markOutgoingMessageTerminalFailed(failed.localOrdinal, "private", now = 3L)
        val queue = SendQueue(
            ownerUid = "user-1",
            localCache = cache,
            connectionState = MutableStateFlow(ConnectionState.DISCONNECTED),
            sender = MessageSender { error("must stay offline") },
            scope = backgroundScope,
            clock = { 10L },
        )

        assertEquals(1L, queue.queueSnapshots.value.terminalFailedCount)
        assertEquals(0L, queue.queueSnapshots.value.pendingOrInFlightCount)
        assertEquals(
            "replacement",
            queue.replaceTerminalFailure(
                "snapshot",
                "failed",
                message("snapshot", "replacement", "user-1"),
            )?.message?.clientMsgId,
        )
        assertEquals(0L, queue.queueSnapshots.value.terminalFailedCount)
        assertEquals(1L, queue.queueSnapshots.value.pendingOrInFlightCount)
        assertEquals(0L, queue.queueSnapshots.value.oldestActiveAgeMs)
        queue.close()
    }

    private fun message(chatId: String, clientMsgId: String, senderUid: String) = Message(
        chatId = chatId,
        clientMsgId = clientMsgId,
        senderUid = senderUid,
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1L,
        body = RichTextBody("hello", plainText = "hello"),
    )
}
