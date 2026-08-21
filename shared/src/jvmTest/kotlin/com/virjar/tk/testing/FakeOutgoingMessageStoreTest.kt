package com.virjar.tk.testing

import com.virjar.tk.body.RichTextBody
import com.virjar.tk.client.OutgoingMessageConflictException
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeOutgoingMessageStoreTest {
    @Test
    fun `cancel wins retry failure and successful ack callbacks in the fake`() {
        val projectionLock = Any()
        val messages = mutableMapOf<String, Message>()
        val store = store(projectionLock, messages)
        val admitted = store.enqueue(message(), 1L)
        store.claim(2L)
        store.cancel("logout", 3L)

        store.retry(admitted.localOrdinal, "late retry", 100L, 4L)
        store.fail(admitted.localOrdinal, "late failure", 5L)
        store.complete(admitted.localOrdinal, com.virjar.tk.protocol.payload.MessageAckPayload("m1", 9L, 0))

        val retained = store.recover(6L).single()
        assertEquals(com.virjar.tk.client.OutgoingMessageState.TERMINAL_FAILED, retained.state)
        assertEquals("logout", retained.lastError)
        assertEquals(Message.SEND_STATUS_FAILED, messages.getValue("m1").sendStatus)
        assertEquals(0L, messages.getValue("m1").serverSeq)
    }

    @Test
    fun `shared projection monitor prevents reset-transition lock inversion`() {
        val projectionLock = Any()
        val messages = mutableMapOf<String, Message>()
        val store = store(projectionLock, messages)
        val admitted = store.enqueue(message(), 1L)
        store.claim(2L)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.execute {
                start.await()
                repeat(500) { iteration ->
                    store.retry(admitted.localOrdinal, "network", 0L, 3L + iteration)
                    store.claim(3L + iteration)
                }
                finished.countDown()
            }
            executor.execute {
                start.await()
                repeat(500) {
                    synchronized(projectionLock) { store.projectionAfterReset() }
                }
                finished.countDown()
            }
            start.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS), "outgoing/reset lock order deadlocked")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `success receipt is retained and cannot be cancelled or replayed`() {
        val projectionLock = Any()
        val messages = mutableMapOf<String, Message>()
        val store = store(projectionLock, messages)
        val admitted = store.enqueue(message(), 1L)
        store.claim(2L)
        store.complete(
            admitted.localOrdinal,
            com.virjar.tk.protocol.payload.MessageAckPayload("m1", 9L, 0),
            now = 3L,
        )

        store.cancel("logout", 4L)

        val receipt = store.get("c1", "m1", null)!!
        assertEquals(com.virjar.tk.client.OutgoingMessageState.SUCCESS, receipt.state)
        assertEquals(9L, receipt.serverSeq)
        assertEquals(null, store.peek())
        assertEquals(9L, messages.getValue("m1").serverSeq)
    }

    @Test
    fun `success GC uses completion order when authority callbacks share one clock tick`() {
        val projectionLock = Any()
        val messages = mutableMapOf<String, Message>()
        val store = store(projectionLock, messages, successReceiptLimit = 1)
        store.enqueue(message().copy(chatId = "c1", clientMsgId = "low-ordinal"), 1L)
        store.enqueue(message().copy(chatId = "c2", clientMsgId = "high-ordinal"), 2L)

        store.promoteFromAuthority(
            message().copy(chatId = "c2", clientMsgId = "high-ordinal", serverSeq = 20L),
            now = 10L,
        )
        store.promoteFromAuthority(
            message().copy(chatId = "c1", clientMsgId = "low-ordinal", serverSeq = 21L),
            now = 10L,
        )

        assertEquals(
            com.virjar.tk.client.OutgoingMessageState.SUCCESS,
            store.get("c1", "low-ordinal", null)?.state,
        )
        assertEquals(null, store.get("c2", "high-ordinal", null))
    }

    @Test
    fun `fake initial window keeps every optimistic row before recent authority`() {
        val cache = FakeLocalCache()
        val failed = cache.enqueueOutgoingMessage(message().copy(clientMsgId = "failed"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        cache.markOutgoingMessageTerminalFailed(failed.localOrdinal, "rejected", now = 3L)
        cache.enqueueOutgoingMessage(message().copy(clientMsgId = "queued", timestamp = 2L), now = 4L)
        (1L..60L).forEach { seq ->
            cache.insertMessage(
                message().copy(
                    clientMsgId = "server-$seq",
                    serverSeq = seq,
                    senderUid = "peer",
                    timestamp = 100L + seq,
                ),
            )
        }

        val window = cache.getMessages("c1", limit = 50)

        assertEquals(50, window.size)
        assertEquals(setOf("queued", "failed"), window.take(2).mapTo(mutableSetOf()) { it.clientMsgId })
        assertEquals((13L..60L).toSet(), window.filter { it.serverSeq > 0L }.mapTo(mutableSetOf()) { it.serverSeq })
    }

    @Test
    fun `fake crowded window reserves an authority anchor and stays bounded`() {
        val cache = FakeLocalCache()
        (1L..20L).forEach { index ->
            cache.enqueueOutgoingMessage(
                message().copy(clientMsgId = "local-$index", timestamp = 1_000L + index),
                now = index,
            )
        }
        (1L..5L).forEach { seq ->
            cache.insertMessage(
                message().copy(
                    clientMsgId = "server-$seq",
                    serverSeq = seq,
                    senderUid = "peer",
                    timestamp = 2_000L + seq,
                ),
            )
        }

        val window = cache.getMessages("c1", limit = 10)

        assertEquals(10, window.size)
        assertEquals(9, window.count { it.serverSeq == 0L })
        assertEquals(listOf(5L), window.filter { it.serverSeq > 0L }.map { it.serverSeq })
    }

    @Test
    fun `fake local seq zero insert cannot replace authority`() {
        val cache = FakeLocalCache()
        val authority = message().copy(serverSeq = 9L, senderUid = "peer")
        cache.insertMessage(authority)

        assertFailsWith<OutgoingMessageConflictException> { cache.insertMessage(message()) }
        assertFailsWith<OutgoingMessageConflictException> {
            cache.enqueueOutgoingMessage(message(), now = 1L)
        }
        assertEquals(authority, cache.getMessages("c1").single())
    }

    private fun store(
        projectionLock: Any,
        messages: MutableMap<String, Message>,
        successReceiptLimit: Int = com.virjar.tk.client.DEFAULT_OUTGOING_SUCCESS_RECEIPTS,
    ) = FakeOutgoingMessageStore(
        lock = projectionLock,
        upsertProjection = { message -> synchronized(projectionLock) { messages[message.clientMsgId] = message } },
        updateProjectionStatus = { message, status ->
            synchronized(projectionLock) { messages[message.clientMsgId] = message.copy(sendStatus = status) }
        },
        completeProjection = { message, seq ->
            synchronized(projectionLock) { messages[message.clientMsgId] = message.copy(serverSeq = seq) }
        },
        successReceiptLimit = successReceiptLimit,
    )

    private fun message() = Message(
        chatId = "c1",
        clientMsgId = "m1",
        senderUid = "u1",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1L,
        body = RichTextBody("hello", plainText = "hello"),
    )
}
