package com.virjar.tk.agent

import com.virjar.tk.bot.ImBotMessageInbox
import com.virjar.tk.bot.PersistentImBotCacheOwner
import com.virjar.tk.bot.awaitAuthenticatedWithProgress
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.EventProcessor
import com.virjar.tk.client.ImClient
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.testing.FakeLocalCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HeadlessSyncSemanticsTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `restarting the same account does not redeliver a committed historical message`() = runBlocking {
        val dataDir = createTempDirectory("imbot-cache-").toFile().also(tempDirs::add)
        val owner = PersistentImBotCacheOwner(dataDir)
        assertFalse(File(dataDir, "users").exists(), "constructing an owner must not guess an account path")
        val message = message("historical", serverSeq = 1L)
        val event = NotifyPayload(
            eventId = 41L,
            notifyType = NotifyType.MESSAGE_RECV.code,
            payload = ProtoCodec.encode(message),
        )

        val firstCache = owner.open("uid-1")
        val firstClient = ImClient()
        val firstInbox = ImBotMessageInbox().also { it.bind(firstCache) }
        try {
            EventProcessor(
                firstClient,
                firstCache,
                durableMessageSink = firstInbox::publish,
            ).processBatch(listOf(event, event.copy(eventId = 42L)))
            assertEquals("historical", firstCache.peekBotMessage()?.message?.clientMsgId)
            assertEquals(41L, firstCache.peekBotMessage()?.eventId)
        } finally {
            firstInbox.close()
            firstCache.close()
            firstClient.destroy()
        }

        val restartedCache = PersistentImBotCacheOwner(dataDir).open("uid-1")
        val restartedClient = ImClient()
        val restartedInbox = ImBotMessageInbox().also { it.bind(restartedCache) }
        try {
            val pending = withTimeout(2_000) { restartedInbox.receivePending() }
            assertEquals("historical", pending.message.clientMsgId, "unacked disk row must survive restart")
            restartedInbox.ack(pending.eventId)

            EventProcessor(
                restartedClient,
                restartedCache,
                durableMessageSink = restartedInbox::publish,
            ).processNotify(event)

            assertEquals(42L, restartedCache.getSyncCursor(EventProcessor.SYNC_CURSOR_KEY))
            assertEquals(null, restartedCache.peekBotMessage(), "acked history must not re-enter the inbox")
        } finally {
            restartedInbox.close()
            restartedCache.close()
            restartedClient.destroy()
        }
    }

    @Test
    fun `agent reliable inbox retains replay and ready-gap messages exactly once`() = runBlocking {
        val dataDir = createTempDirectory("agent-inbox-").toFile().also(tempDirs::add)
        val cache = FakeLocalCache()
        val inbox = ImBotMessageInbox().also { it.bind(cache) }
        val replay = message("replay", serverSeq = 1L)
        val readyGap = message("ready-gap", serverSeq = 2L)

        // Simulate replay arriving before AgentRuntime has installed its consumer.
        cache.insertMessage(replay)
        inbox.publish(1L, replay)
        val runtime = AgentRuntime("127.0.0.1", 5100, dataDir, "http://127.0.0.1", inbox)
        try {
            // Simulate a live message on the SYNC_READY -> attach boundary.
            cache.insertMessage(readyGap)
            inbox.publish(2L, readyGap)
            withTimeout(2_000) {
                while (runtime.bufferedCount < 2) delay(1)
            }

            assertEquals(
                listOf("replay", "ready-gap"),
                runtime.bufferedMessages(chatId = null, limit = 10, afterSeq = 0L)
                    .map(Message::clientMsgId),
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `disk inbox projects backlog larger than former memory capacity without a consumer`() = runBlocking {
        val cache = FakeLocalCache()
        val inbox = ImBotMessageInbox().also { it.bind(cache) }
        val client = ImClient()
        val processor = EventProcessor(client, cache, durableMessageSink = inbox::publish)
        val events = (1L..600L).map { eventId ->
            NotifyPayload(
                eventId = eventId,
                notifyType = NotifyType.MESSAGE_RECV.code,
                payload = ProtoCodec.encode(message("backlog-$eventId", eventId)),
            )
        }
        try {
            withTimeout(2_000) { processor.processBatch(events) }
            assertEquals(600L, processor.lastEventId.value)
            repeat(events.size) { index ->
                val pending = inbox.receivePending()
                assertEquals("backlog-${index + 1}", pending.message.clientMsgId)
                inbox.ack(pending.eventId)
            }
            assertEquals(null, cache.peekBotMessage())
        } finally {
            inbox.close()
            client.destroy()
        }
    }

    @Test
    fun `different event ids for the same authoritative message enqueue only once`() = runBlocking {
        val cache = FakeLocalCache()
        val inbox = ImBotMessageInbox().also { it.bind(cache) }
        val client = ImClient()
        val processor = EventProcessor(client, cache, durableMessageSink = inbox::publish)
        val duplicate = message("same-message", serverSeq = 9L)
        try {
            processor.processBatch(
                listOf(101L, 102L).map { eventId ->
                    NotifyPayload(
                        eventId = eventId,
                        notifyType = NotifyType.MESSAGE_RECV.code,
                        payload = ProtoCodec.encode(duplicate),
                    )
                },
            )

            val pending = inbox.receivePending()
            assertEquals(101L, pending.eventId)
            inbox.ack(pending.eventId)
            assertEquals(null, cache.peekBotMessage())
            assertEquals(102L, processor.lastEventId.value)
        } finally {
            inbox.close()
            client.destroy()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `long synchronization keeps renewing authentication no-progress timeout`() = runTest {
        val state = MutableStateFlow(ConnectionState.SYNCHRONIZING)
        val cursor = MutableStateFlow(0L)
        val waiting = async {
            awaitAuthenticatedWithProgress(state, cursor, noProgressTimeoutMs = 100L)
        }

        repeat(4) { index ->
            advanceTimeBy(75L)
            cursor.value = index + 1L
            runCurrent()
        }
        // Total elapsed time is now 300ms (> 100ms), but every 75ms committed progress.
        advanceTimeBy(75L)
        state.value = ConnectionState.AUTHENTICATED
        runCurrent()

        waiting.await()
    }

    private fun message(clientMsgId: String, serverSeq: Long) = Message(
        chatId = "chat-1",
        clientMsgId = clientMsgId,
        serverSeq = serverSeq,
        senderUid = "peer",
        messageType = 1,
        timestamp = serverSeq,
    )
}
