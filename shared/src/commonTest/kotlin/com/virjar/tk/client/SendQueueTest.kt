package com.virjar.tk.client

import com.virjar.tk.body.RichTextBody
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.testing.FakeLocalCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SendQueueTest {
    @Test
    fun `ack classification keeps only ambiguous and transient responses retryable`() {
        assertEquals(
            OutgoingAckDisposition.RETRYABLE,
            classifyOutgoingAck(MessageAckPayload("m1", 0L, -1, "ACK timeout"), "m1"),
        )
        listOf(500, 503, 599).forEach { code ->
            assertEquals(
                OutgoingAckDisposition.RETRYABLE,
                classifyOutgoingAck(MessageAckPayload("m1", 0L, code, "server unavailable"), "m1"),
            )
        }
        assertEquals(
            OutgoingAckDisposition.SUCCESS,
            classifyOutgoingAck(MessageAckPayload("m1", 9L, 0), "m1"),
        )
        listOf(
            MessageAckPayload("m1", 0L, 400, "rejected"),
            MessageAckPayload("other", 9L, 0, null),
            MessageAckPayload("m1", 0L, 0, null),
        ).forEach { ack ->
            assertEquals(OutgoingAckDisposition.TERMINAL, classifyOutgoingAck(ack, "m1"))
        }
    }

    @Test
    fun `transport loss keeps the head item and retries after authentication`() = runTest {
        val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
        val sent = CompletableDeferred<Unit>()
        val failed = mutableListOf<String>()
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
                    throw AckTransportDisconnectedException()
                }
                com.virjar.tk.protocol.payload.MessageAckPayload(message.clientMsgId, 9L, 0)
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
                        message.clientMsgId,
                        serverSeq = 0L,
                        code = -1,
                        reason = "ACK timeout",
                    )
                } else {
                    com.virjar.tk.protocol.payload.MessageAckPayload(message.clientMsgId, 9L, 0)
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
        val failure = CompletableDeferred<String>()
        val queue = SendQueue(
            ownerUid = "user-1",
            localCache = FakeLocalCache(),
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            sender = MessageSender { throw IllegalArgumentException("附件路径非法") },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            onFailed = { _, reason -> failure.complete(reason) },
        )

        queue.enqueue(message("chat-1", "client-1", "user-1"))

        assertEquals("附件路径非法", withTimeout(1_000) { failure.await() })
        queue.close()
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
                com.virjar.tk.protocol.payload.MessageAckPayload(message.clientMsgId, 11L, 0)
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

    private fun message(chatId: String, clientMsgId: String, senderUid: String) = Message(
        chatId = chatId,
        clientMsgId = clientMsgId,
        senderUid = senderUid,
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1L,
        body = RichTextBody("hello", plainText = "hello"),
    )
}
