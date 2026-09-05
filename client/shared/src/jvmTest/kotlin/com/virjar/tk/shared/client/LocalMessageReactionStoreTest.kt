package com.virjar.tk.shared.client

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.protocol.model.MessageReactionSummary
import com.virjar.tk.protocol.MessageReactionEventPayload
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.shared.repository.MessageRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * CLIENT-05 表情回应的本地投影：行级 delta、权威快照替换、撤回清理、
 * 观察者生命周期与消息保留窗口绑定的回收。
 */
class LocalMessageReactionStoreTest {

    private val drivers = mutableListOf<SqlDriver>()

    private fun newCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        drivers += driver
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(
            driver = driver,
            outboxLimits = DEFAULT_LOCAL_OUTBOX_LIMITS,
        )
    }

    @AfterTest
    fun closeDrivers() {
        drivers.forEach { driver ->
            runCatching { driver.close() }
        }
        drivers.clear()
    }

    private fun delta(chatId: String, seq: Long, emoji: String, uid: String, add: Boolean = true) =
        MessageReactionEventPayload(
            chatId = chatId,
            serverSeq = seq,
            emoji = emoji,
            actorUid = uid,
            action = if (add) 1 else 0,
        )

    private fun summary(seq: Long, vararg groups: Pair<String, List<String>>) = MessageReactionSummary(
        serverSeq = seq,
        groups = groups.map { (emoji, uids) -> MessageReactionGroup(emoji, uids) },
    )

    @Test
    fun `delta applies are idempotent and the aggregate derives from rows`() = runTest {
        val cache = newCache()
        cache.applyMessageReactionDelta(delta("c1", 5L, "👍", "u1"))
        cache.applyMessageReactionDelta(delta("c1", 5L, "👍", "u2"))
        // 重复事件重放收敛
        cache.applyMessageReactionDelta(delta("c1", 5L, "👍", "u1"))
        cache.applyMessageReactionDelta(delta("c1", 5L, "🎉", "u1"))

        val observed = cache.observeMessageReactions("c1").first()
        // 聚合按 emoji canonical 排序
        assertEquals(
            mapOf(
                5L to listOf(
                    MessageReactionGroup("🎉", listOf("u1")),
                    MessageReactionGroup("👍", listOf("u1", "u2")),
                ),
            ),
            observed,
        )

        // remove delta 收敛；再次 remove 是无操作
        cache.applyMessageReactionDelta(delta("c1", 5L, "🎉", "u1", add = false))
        cache.applyMessageReactionDelta(delta("c1", 5L, "🎉", "u1", add = false))
        assertEquals(
            mapOf(5L to listOf(MessageReactionGroup("👍", listOf("u1", "u2")))),
            cache.observeMessageReactions("c1").first(),
        )
        cache.close()
    }

    @Test
    fun `repository replaces the complete range including empty responses without touching neighbors`() = runTest {
        val cache = newCache()
        cache.applyMessageReactionDelta(delta("c2", 1L, "👍", "u1"))
        cache.applyMessageReactionDelta(delta("c2", 9L, "🎉", "u9"))
        cache.applyMessageReactionDelta(delta("other", 1L, "🎉", "u9"))
        val rpc = FakeRpcInvoker().apply {
            enqueueOk(ProtoCodec.encodeList(listOf(
                summary(1L, "❤️" to listOf("u2", "u3")),
                summary(2L, "👍" to listOf("u1")),
            )))
            enqueueOk(ProtoCodec.encodeList(emptyList()))
        }
        val repository = MessageRepository(rpc, cache)
        repository.loadReactions("c2", 1L, 2L).getOrThrow()
        assertEquals(
            mapOf(
                1L to listOf(MessageReactionGroup("❤️", listOf("u2", "u3"))),
                2L to listOf(MessageReactionGroup("👍", listOf("u1"))),
                9L to listOf(MessageReactionGroup("🎉", listOf("u9"))),
            ),
            cache.observeMessageReactions("c2").first(),
        )

        // 服务端不为“没有回应”创建空 summary，而是直接省略该 seq。
        repository.loadReactions("c2", 1L, 1L).getOrThrow()
        assertEquals(
            mapOf(
                2L to listOf(MessageReactionGroup("👍", listOf("u1"))),
                9L to listOf(MessageReactionGroup("🎉", listOf("u9"))),
            ),
            cache.observeMessageReactions("c2").first(),
        )
        assertEquals(
            mapOf(1L to listOf(MessageReactionGroup("🎉", listOf("u9")))),
            cache.observeMessageReactions("other").first(),
        )
        cache.close()
    }

    @Test
    fun `delta during RPC rejects the stale snapshot and one retry fills a stationary window`() = runTest {
        val cache = newCache()
        cache.applyMessageReactionDelta(delta("race", 1L, "👍", "old"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val response = FakeRpcInvoker().apply {
            enqueueOk(ProtoCodec.encodeList(listOf(summary(1L, "👍" to listOf("old")))))
            enqueueOk(ProtoCodec.encodeList(listOf(
                summary(1L, "🎉" to listOf("new")),
                summary(2L, "👍" to listOf("history")),
            )))
        }
        val delayedRpc = object : RpcInvoker {
            override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                entered.complete(Unit)
                release.await()
                if (response.calls.isNotEmpty()) {
                    assertEquals(
                        mapOf(1L to listOf(MessageReactionGroup("🎉", listOf("new")))),
                        cache.observeMessageReactions("race").first(),
                    )
                }
                return response.invoke(service, methodId, payload)
            }
        }
        val request = async {
            MessageRepository(delayedRpc, cache).loadReactions("race", 1L, 2L)
        }
        entered.await()
        cache.applyMessageReactionDelta(delta("race", 1L, "👍", "old", add = false))
        cache.applyMessageReactionDelta(delta("race", 1L, "🎉", "new"))
        release.complete(Unit)

        request.await().getOrThrow()
        assertEquals(2, response.calls.size)
        assertEquals(
            mapOf(
                1L to listOf(MessageReactionGroup("🎉", listOf("new"))),
                2L to listOf(MessageReactionGroup("👍", listOf("history"))),
            ),
            cache.observeMessageReactions("race").first(),
        )
        cache.close()
    }

    @Test
    fun `continuous changes stop after one snapshot retry and keep the event projection`() = runTest {
        val cache = newCache()
        val responses = FakeRpcInvoker().apply {
            repeat(2) { enqueueOk(ProtoCodec.encodeList(emptyList())) }
        }
        val rpc = object : RpcInvoker {
            override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                val response = responses.invoke(service, methodId, payload)
                cache.applyMessageReactionDelta(delta("busy", 1L, "👍", "u${responses.calls.size}"))
                return response
            }
        }
        assertFailsWith<CancellationException> {
            MessageRepository(rpc, cache).loadReactions("busy", 1L, 1L)
        }
        assertEquals(2, responses.calls.size)
        assertEquals(
            mapOf(1L to listOf(MessageReactionGroup("👍", listOf("u1", "u2")))),
            cache.observeMessageReactions("busy").first(),
        )
        cache.close()
    }

    @Test
    fun `duplicate summary seq is rejected`() = runTest {
        val cache = newCache()
        val error = runCatching {
            cache.applyMessageReactionSnapshot(
                cache.beginMessageReactionSnapshot("c3"),
                "c3",
                1L,
                1L,
                listOf(summary(1L, "👍" to listOf("u1")), summary(1L, "❤️" to listOf("u2"))),
            )
        }.exceptionOrNull()
        kotlin.test.assertTrue(error is IllegalArgumentException)
        cache.close()
    }

    @Test
    fun `revoked message projection clears its reactions`() = runTest {
        val cache = newCache()
        cache.applyMessageReactionDelta(delta("c4", 7L, "👍", "u1"))
        cache.insertMessage(
            message("c4", 7L, flags = Message.FLAG_REVOKED),
        )
        assertEquals(emptyMap(), cache.observeMessageReactions("c4").first())
        cache.close()
    }

    @Test
    fun `residents persist rows and a fresh observer reloads from sqlite`() = runTest {
        val cache = newCache()
        cache.applyMessageReactionDelta(delta("c5", 3L, "👍", "u9"))
        // 第一个观察者结束后 resident 释放；第二个观察者从 SQL 重建同一聚合。
        val first = cache.observeMessageReactions("c5").take(1).toList()
        assertEquals(1, first.size)
        assertEquals(
            mapOf(3L to listOf(MessageReactionGroup("👍", listOf("u9")))),
            cache.observeMessageReactions("c5").first(),
        )
        cache.close()
    }

    @Test
    fun `observers see live updates while collecting`() = runTest {
        val cache = newCache()
        val received = mutableListOf<Map<Long, List<MessageReactionGroup>>>()
        val job = launch {
            cache.observeMessageReactions("c6").take(2).toList(received)
        }
        // 等待观察者就位（首帧已发出）
        kotlinx.coroutines.delay(100)
        cache.applyMessageReactionDelta(delta("c6", 1L, "👍", "u1"))
        job.join()
        assertEquals(
            listOf(
                emptyMap(),
                mapOf(1L to listOf(MessageReactionGroup("👍", listOf("u1")))),
            ),
            received,
        )
        coroutineContext.cancelChildren()
        cache.close()
    }

    @Test
    fun `server projection reset clears reaction rows`() = runTest {
        val cache = newCache()
        cache.applyMessageReactionDelta(delta("c7", 2L, "👍", "u1"))
        val datasetId = "123e4567-e89b-42d3-a456-426614174000"
        cache.bindSyncDataset(datasetId)
        cache.resetServerProjection(datasetId)
        assertEquals(emptyMap(), cache.observeMessageReactions("c7").first())
        cache.close()
    }

    @Test
    fun `checkpoint atomically clears reactions and invalidates in-flight snapshots`() = runTest {
        val cache = newCache()
        cache.bindSyncDataset(TEST_SYNC_DATASET_ID)
        cache.applyMessageReactionDelta(delta("checkpoint", 2L, "👍", "u1"))
        val oldRequest = cache.beginMessageReactionSnapshot("checkpoint")
        val checkpoint = ServerProjectionCheckpoint(
            datasetId = TEST_SYNC_DATASET_ID,
            baseEventId = 10L,
            currentUser = User("u1", "user", "User"),
            contacts = emptyList(),
            chats = emptyList(),
            conversations = emptyList(),
        )
        cache.observeMessageReactions("checkpoint").test {
            assertEquals(mapOf(2L to listOf(MessageReactionGroup("👍", listOf("u1")))), awaitItem())
            assertFailsWith<IllegalStateException> {
                cache.applyServerProjectionCheckpoint(TEST_SYNC_DATASET_ID, 1L, checkpoint)
            }
            expectNoEvents()
            cache.applyServerProjectionCheckpoint(TEST_SYNC_DATASET_ID, 0L, checkpoint)
            assertEquals(emptyMap(), awaitItem())
            assertFalse(
                cache.applyMessageReactionSnapshot(
                    oldRequest, "checkpoint", 2L, 2L,
                    listOf(summary(2L, "👍" to listOf("u1"))),
                ),
            )
            expectNoEvents()
        }
        assertEquals(emptyMap(), cache.observeMessageReactions("checkpoint").first())
        assertEquals(10L, cache.getSyncState()?.cursor)
        cache.close()
    }

    @Test
    fun `chat deletion clears live and persisted reactions without touching another chat`() = runTest {
        val cache = newCache()
        try {
            cache.insertMessage(message("removed", 1L))
            cache.insertMessage(message("retained", 1L))
            cache.applyMessageReactionDelta(delta("removed", 1L, "👍", "u1"))
            cache.applyMessageReactionDelta(delta("retained", 1L, "👍", "u2"))

            cache.observeMessageReactions("removed").test {
                assertEquals(mapOf(1L to listOf(MessageReactionGroup("👍", listOf("u1")))), awaitItem())
                cache.deleteChat("removed")
                assertEquals(emptyMap(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            // 前一个观察者退出后，从 SQLite 重新加载，不只检查内存已清空。
            assertEquals(emptyMap(), cache.observeMessageReactions("removed").first())
            assertEquals(
                mapOf(1L to listOf(MessageReactionGroup("👍", listOf("u2")))),
                cache.observeMessageReactions("retained").first(),
            )
        } finally {
            cache.close()
        }
    }

    @Test
    fun `message retention prunes orphan reaction rows in the same transaction`() = runTest {
        val root = kotlin.io.path.createTempDirectory("reaction-retention-").toFile()
        val databaseFile = root.resolve("cache.db")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}").let { driver ->
                AppDatabase.Schema.create(driver)
                val queries = AppDatabase(driver).appDatabaseQueries
                (1L..5L).forEach { seq ->
                    queries.insertMessage(
                        "chat",
                        "m-$seq",
                        seq,
                        "u1",
                        MessageType.RICH_TEXT.code.toLong(),
                        seq,
                        0L,
                        null,
                        Message.SEND_STATUS_SENT.toLong(),
                    )
                    queries.insertMessageReaction("chat", seq, "👍", "u$seq")
                }
                driver.close()
            }

            val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
            drivers += driver
            val cache = LocalCacheImpl(
                driver = driver,
                outboxLimits = DEFAULT_LOCAL_OUTBOX_LIMITS,
                messageRetentionLimits = LocalMessageRetentionLimits(retainedCount = 2),
            )
            // 一条新的权威消息写入触发保留修剪：seq 1-4 被删，orphan 回收同步删除其回应。
            cache.insertMessage(message("chat", 6L))
            val queries = AppDatabase(driver).appDatabaseQueries
            val remainingReactions = queries.selectMessageReactionsForChat("chat").executeAsList()
                .map { it.server_seq }.sorted()
            // seq 6 没有回应；保留窗口只余 seq 5/6 的消息行，seq 5 的回应仍在
            assertEquals(listOf(5L), remainingReactions)
            cache.close()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun message(chatId: String, seq: Long, flags: Int = 0) = Message(
        chatId = chatId,
        clientMsgId = "m-$seq",
        serverSeq = seq,
        senderUid = "u1",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = seq,
        flags = flags,
        body = com.virjar.tk.protocol.body.buildRichTextBody("hi"),
    )
}
