package com.virjar.tk.shared.agent

import com.virjar.tk.shared.bot.ImBotMessageInbox
import com.virjar.tk.shared.bot.PersistentImBotCacheOwner
import com.virjar.tk.shared.bot.awaitAuthenticatedWithProgress
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.EventProcessor
import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.PendingBotMessage
import com.virjar.tk.shared.client.TEST_SYNC_DATASET_ID
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.shared.testkit.FakeLocalCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

        val firstCache = owner.open(
            TEST_AGENT_DEPLOYMENT_IDENTITY,
            TEST_SYNC_DATASET_ID,
            "uid-1",
        ).also { it.bindSyncDataset(TEST_SYNC_DATASET_ID) }
        val firstClient = ImClient()
        val firstInbox = ImBotMessageInbox().also { it.bind(firstCache) }
        try {
            EventProcessor(
                firstClient,
                firstCache,
                durableMessageSink = firstInbox::publish,
            ).processBatch(listOf(event, event))
            assertEquals("historical", firstCache.peekBotMessage()?.message?.clientMsgId)
            assertEquals(41L, firstCache.peekBotMessage()?.eventId)
        } finally {
            firstInbox.close()
            firstCache.close()
            firstClient.destroy()
        }

        val restartedCache = PersistentImBotCacheOwner(dataDir)
            .open(TEST_AGENT_DEPLOYMENT_IDENTITY, TEST_SYNC_DATASET_ID, "uid-1")
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

            assertEquals(TEST_SYNC_DATASET_ID, restartedCache.getSyncState()?.datasetId)
            assertEquals(41L, restartedCache.getSyncState()?.cursor)
            assertEquals(null, restartedCache.peekBotMessage(), "acked history must not re-enter the inbox")
        } finally {
            restartedInbox.close()
            restartedCache.close()
            restartedClient.destroy()
        }

        val historyCache = PersistentImBotCacheOwner(dataDir)
            .open(TEST_AGENT_DEPLOYMENT_IDENTITY, TEST_SYNC_DATASET_ID, "uid-1")
        try {
            assertEquals(null, historyCache.peekBotMessage(), "acked delivery must not replay after restart")
            assertEquals(
                listOf(41L),
                historyCache.listBotMessageDeliveries(0L, null, 10).map { it.eventId },
                "acked delivery must remain cursor-readable after restart",
            )
        } finally {
            historyCache.close()
        }
    }

    @Test
    fun `agent reliable inbox retains replay and ready-gap messages exactly once`() = runBlocking {
        val root = createAgentSecurityTestRoot("agent-inbox-").also(tempDirs::add)
        val dataDir = File(root, "agent-data")
        val cache = FakeLocalCache()
        val inbox = ImBotMessageInbox().also { it.bind(cache) }
        val replay = message("replay", serverSeq = 1L)
        val readyGap = message("ready-gap", serverSeq = 2L)

        // 模拟回放在 AgentRuntime 装入其消费者之前到达。
        cache.insertMessage(replay)
        inbox.publish(1L, replay)
        val runtime = AgentRuntime("127.0.0.1", 5100, dataDir, "http://127.0.0.1", inbox)
        try {
            // 模拟一条在 SYNC_READY → attach 边界上的实时消息。
            cache.insertMessage(readyGap)
            inbox.publish(2L, readyGap)
            withTimeout(2_000) {
                while (runtime.bufferedCount < 2) delay(1)
            }

            assertEquals(
                listOf("replay", "ready-gap"),
                runtime.bufferedMessages(chatId = null, limit = 10, afterEventId = 0L)
                    .map { it.message.clientMsgId },
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
    fun `different event ids for one server sequence remain distinct deliveries`() = runBlocking {
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
            val next = inbox.receivePending()
            assertEquals(102L, next.eventId)
            inbox.ack(next.eventId)
            assertEquals(null, cache.peekBotMessage())
            assertEquals(102L, processor.lastEventId.value)
        } finally {
            inbox.close()
            client.destroy()
        }
    }

    @Test
    fun `agent delivery cursor is global retryable and no-cursor wait observes only new messages`() = runBlocking {
        val root = createAgentSecurityTestRoot("agent-cursor-").also(tempDirs::add)
        val dataDir = File(root, "agent-data")
        val cache = FakeLocalCache()
        val inbox = ImBotMessageInbox().also { it.bind(cache) }
        inbox.publish(10L, message("chat-a-1", serverSeq = 1L, chatId = "chat-a"))
        inbox.publish(11L, message("chat-b-1", serverSeq = 1L, chatId = "chat-b"))
        val runtime = AgentRuntime("127.0.0.1", 5100, dataDir, "http://127.0.0.1", inbox)
        try {
            withTimeout(2_000) {
                while (cache.peekBotMessage() != null) delay(1)
            }
            assertEquals(
                listOf(10L, 11L),
                runtime.bufferedMessages(null, 10, 0L).map { it.eventId },
            )
            val firstPage = runtime.bufferedMessages(null, 1, 0L)
            val secondPage = runtime.bufferedMessages(null, 1, firstPage.single().eventId)
            assertEquals(
                listOf(10L, 11L),
                (firstPage + secondPage).map { it.eventId },
                "advancing by the last eventId must neither skip nor repeat a delivery",
            )
            assertEquals(
                listOf(11L),
                runtime.bufferedMessages(null, 10, 10L).map { it.eventId },
            )
            assertEquals(
                listOf(10L),
                runtime.bufferedMessages("chat-a", 10, 0L).map { it.eventId },
                "chat filtering must not reinterpret serverSeq as the global cursor",
            )
            assertEquals(
                11L,
                runtime.bufferedMessages(null, 10, 10L).single().eventId,
                "retrying the same cursor must return the same first page",
            )
            assertEquals(11L, runtime.waitMessage(0L, "chat-b", 1).delivery?.eventId)

            val waiting = async(Dispatchers.Default) { runtime.waitMessage(null, null, 2) }
            delay(25)
            assertFalse(waiting.isCompleted, "no-cursor wait must not replay the latest historical row")
            inbox.publish(12L, message("chat-a-2", serverSeq = 2L, chatId = "chat-a"))
            assertEquals(12L, withTimeout(2_000) { waiting.await() }.delivery?.eventId)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `recv wait final read closes persisted-notified race and close releases waiters`() = runBlocking {
        val root = createAgentSecurityTestRoot("agent-wait-race-").also(tempDirs::add)
        val dataDir = File(root, "agent-data")
        val cache = FakeLocalCache()
        val inbox = ImBotMessageInbox().also { it.bind(cache) }
        val runtime = AgentRuntime("127.0.0.1", 5100, dataDir, "http://127.0.0.1", inbox)
        try {
            val waiting = async(Dispatchers.Default) { runtime.waitMessage(null, null, 1) }
            delay(25)
            // 持久化但不通知 inbox：模拟超时跑在延迟通知之前。
            cache.enqueueBotMessage(20L, message("persisted-before-notify", 1L))
            val result = withTimeout(2_000) { waiting.await() }
            assertEquals(20L, result.delivery?.eventId)
            assertEquals(20L, result.nextEventId)

            val blocked = async(Dispatchers.Default) { runtime.waitMessage(20L, null, 60) }
            delay(25)
            runtime.close()
            assertEquals(20L, withTimeout(2_000) { blocked.await() }.nextEventId)
        } finally {
            runCatching { runtime.close() }
        }
    }

    @Test
    fun `chat tombstone linearizes stale peek before agent notify without purging another chat`() = runBlocking {
        val root = createAgentSecurityTestRoot("agent-tombstone-gate-").also(tempDirs::add)
        val deletedChatId = "deleted-chat"
        val retainedChatId = "retained-chat"
        val staleCandidatePeeked = CountDownLatch(1)
        val waiterRegisteredAfterTombstone = CountDownLatch(1)
        val releaseStaleCandidate = CountDownLatch(1)
        val firstDeletedPeek = AtomicBoolean(true)
        val deletedDeliveryQueries = AtomicInteger()
        val backing = FakeLocalCache()
        val cache = object : LocalCache by backing {
            override fun peekBotMessage(): PendingBotMessage? {
                val candidate = backing.peekBotMessage()
                if (
                    candidate?.message?.chatId == deletedChatId &&
                    firstDeletedPeek.compareAndSet(true, false)
                ) {
                    staleCandidatePeeked.countDown()
                    check(releaseStaleCandidate.await(5, TimeUnit.SECONDS)) {
                        "timed out releasing stale durable inbox candidate"
                    }
                }
                return candidate
            }

            override fun listBotMessageDeliveries(
                afterEventId: Long,
                chatId: String?,
                limit: Int,
            ): List<PendingBotMessage> {
                val deliveries = backing.listBotMessageDeliveries(afterEventId, chatId, limit)
                if (chatId == deletedChatId && deletedDeliveryQueries.incrementAndGet() == 2) {
                    // waitMessage 只有在它的 waiter 注册之后才会做这第二次读取。
                    waiterRegisteredAfterTombstone.countDown()
                }
                return deliveries
            }
        }
        val inbox = ImBotMessageInbox().also { it.bind(cache) }
        inbox.publish(1L, message("deleted-before-tombstone", 1L, deletedChatId))
        inbox.publish(2L, message("retained", 1L, retainedChatId))
        val runtime = AgentRuntime(
            "127.0.0.1",
            5100,
            File(root, "agent-data"),
            "http://127.0.0.1",
            inbox,
        )
        val client = ImClient()
        val processor = EventProcessor(
            client,
            cache,
            durableChatTombstoneSink = inbox::applyChatTombstone,
        )
        try {
            assertTrue(staleCandidatePeeked.await(5, TimeUnit.SECONDS))
            processor.processNotify(
                NotifyPayload(
                    eventId = 3L,
                    notifyType = NotifyType.CHAT_DELETED.code,
                    payload = ProtoCodec.encode(Chat(chatId = deletedChatId, chatType = 2)),
                ),
            )

            val deletedWaiter = async(Dispatchers.Default) {
                runtime.waitMessage(afterEventId = 0L, chatId = deletedChatId, timeoutSec = 60)
            }
            assertTrue(waiterRegisteredAfterTombstone.await(5, TimeUnit.SECONDS))
            releaseStaleCandidate.countDown()

            withTimeout(2_000) {
                while (backing.peekBotMessage() != null) delay(1)
            }
            assertFalse(deletedWaiter.isCompleted, "post-tombstone waiter received a stale peek notification")
            assertTrue(runtime.bufferedMessages(deletedChatId, 10, 0L).isEmpty())
            assertEquals(
                listOf(2L),
                runtime.bufferedMessages(retainedChatId, 10, 0L).map { it.eventId },
                "the tombstone gate must not purge or suppress another chat",
            )
            assertEquals(2L, runtime.waitMessage(0L, retainedChatId, 1).delivery?.eventId)

            runtime.close()
            assertNull(withTimeout(2_000) { deletedWaiter.await() }.delivery)
        } finally {
            releaseStaleCandidate.countDown()
            runCatching { runtime.close() }
            client.destroy()
            backing.close()
        }
    }

    @Test
    fun `recv wait propagates a durable query failure instead of returning an empty result`() {
        val root = createAgentSecurityTestRoot("agent-wait-query-failure-").also(tempDirs::add)
        val backing = FakeLocalCache()
        val queryCalls = AtomicInteger()
        val cache = object : LocalCache by backing {
            override fun listBotMessageDeliveries(
                afterEventId: Long,
                chatId: String?,
                limit: Int,
            ): List<PendingBotMessage> {
                if (queryCalls.incrementAndGet() == 1) return emptyList()
                throw DurableQueryFailure()
            }
        }
        val inbox = ImBotMessageInbox().also { it.bind(cache) }
        val runtime = AgentRuntime(
            "127.0.0.1",
            5100,
            File(root, "agent-data"),
            "http://127.0.0.1",
            inbox,
        )
        try {
            assertFailsWith<DurableQueryFailure> { runtime.waitMessage(0L, null, 1) }
            assertEquals(2, queryCalls.get(), "failure must come from the post-registration durable read")
        } finally {
            runtime.close()
            backing.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `long synchronization keeps renewing authentication no-progress timeout`() = runTest {
        val state = MutableStateFlow(ConnectionState.SYNCHRONIZING)
        val cursor = MutableStateFlow(0L)
        val waiting = async {
            awaitAuthenticatedWithProgress(
                state,
                cursor,
                MutableStateFlow(0L),
                noProgressTimeoutMs = 100L,
            )
        }

        repeat(4) { index ->
            advanceTimeBy(75L)
            cursor.value = index + 1L
            runCurrent()
        }
        // 现在总耗时是 300ms（> 100ms），但每 75ms 都有一次已提交的进展。
        advanceTimeBy(75L)
        state.value = ConnectionState.AUTHENTICATED
        runCurrent()

        waiting.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `checkpoint page pulses renew timeout while the durable cursor stays fixed`() = runTest {
        val state = MutableStateFlow(ConnectionState.SYNCHRONIZING)
        val cursor = MutableStateFlow(8L)
        val progress = MutableStateFlow(0L)
        val waiting = async {
            awaitAuthenticatedWithProgress(state, cursor, progress, noProgressTimeoutMs = 100L)
        }

        repeat(4) { revision ->
            advanceTimeBy(75L)
            progress.value = revision + 1L
            runCurrent()
        }
        advanceTimeBy(75L)
        state.value = ConnectionState.AUTHENTICATED
        runCurrent()

        waiting.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cursor rollback and replay do not renew authentication timeout`() = runTest {
        val state = MutableStateFlow(ConnectionState.SYNCHRONIZING)
        val cursor = MutableStateFlow(8L)
        val progress = MutableStateFlow(0L)
        val waiting = async {
            runCatching {
                awaitAuthenticatedWithProgress(state, cursor, progress, noProgressTimeoutMs = 100L)
            }
        }

        advanceTimeBy(40L)
        cursor.value = -1L
        runCurrent()
        advanceTimeBy(40L)
        cursor.value = 8L
        runCurrent()
        advanceTimeBy(20L)
        runCurrent()

        assertIs<kotlinx.coroutines.TimeoutCancellationException>(waiting.await().exceptionOrNull())
    }

    private fun message(clientMsgId: String, serverSeq: Long, chatId: String = "chat-1") = Message(
        chatId = chatId,
        clientMsgId = clientMsgId,
        serverSeq = serverSeq,
        senderUid = "peer",
        messageType = 1,
        timestamp = serverSeq,
    )

    private class DurableQueryFailure : RuntimeException()
}
