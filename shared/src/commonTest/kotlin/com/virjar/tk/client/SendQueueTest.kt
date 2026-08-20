package com.virjar.tk.client

import com.virjar.tk.model.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SendQueueTest {
    @Test
    fun `transport loss keeps the head item and retries after authentication`() = runTest {
        val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
        val sent = CompletableDeferred<Unit>()
        val failed = mutableListOf<String>()
        var attempts = 0
        val queue = SendQueue(
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
            onSent = { _, _ -> sent.complete(Unit) },
            onFailed = { _, reason -> failed += reason },
        )

        queue.enqueue(
            Message(
                chatId = "chat-retry",
                clientMsgId = "client-retry",
                senderUid = "user-1",
                messageType = 1,
                timestamp = 1,
            ),
        )
        runCurrent()
        assertEquals(1, attempts)

        state.value = ConnectionState.AUTHENTICATED
        assertEquals(Unit, withTimeout(1_000) { sent.await() })

        assertEquals(2, attempts)
        assertEquals(emptyList(), failed)
        queue.close()
    }

    @Test
    fun `SDK validation failure is reported instead of disguised as timeout`() = runTest {
        val failure = CompletableDeferred<String>()
        val queue = SendQueue(
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            sender = MessageSender { throw IllegalArgumentException("附件路径非法") },
            scope = backgroundScope,
            onFailed = { _, reason -> failure.complete(reason) },
        )

        queue.enqueue(
            Message(
                chatId = "chat-1",
                clientMsgId = "client-1",
                senderUid = "user-1",
                messageType = 1,
                timestamp = 1,
            ),
        )

        assertEquals("附件路径非法", withTimeout(1_000) { failure.await() })
        queue.close()
    }

    @Test
    fun `ack returning after close cannot publish sent or failed callback`() = runTest {
        val senderEntered = CompletableDeferred<Unit>()
        val releaseAck = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<String>()
        val queue = SendQueue(
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            sender = MessageSender { message ->
                senderEntered.complete(Unit)
                releaseAck.await()
                com.virjar.tk.protocol.payload.MessageAckPayload(message.clientMsgId, 11L, 0)
            },
            scope = backgroundScope,
            onSent = { _, _ -> callbacks += "sent" },
            onFailed = { _, _ -> callbacks += "failed" },
        )

        queue.enqueue(
            Message(
                chatId = "retired",
                clientMsgId = "late-ack",
                senderUid = "u1",
                messageType = 1,
                timestamp = 1L,
            ),
        )
        runCurrent()
        assertTrue(senderEntered.isCompleted)

        queue.close()
        releaseAck.complete(Unit)
        runCurrent()

        assertEquals(emptyList(), callbacks)
    }
}
