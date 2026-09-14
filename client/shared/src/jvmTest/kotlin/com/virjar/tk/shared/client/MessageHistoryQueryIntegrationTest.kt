package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.MessageRpcContract
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.MessageRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 独立 SDK 历史查询使用真实 SQLite，不能暗中依赖 UI pager 或覆盖本地投影。 */
class MessageHistoryQueryIntegrationTest {
    @Test
    fun `explicit cursor and latest then older queries work without a pager or cache writes`() = runTest {
        withCache { cache ->
            val latest = (11L downTo 2L).map(::message)
            val oldest = listOf(message(1))
            val rpc = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encodeList(oldest))
                enqueueOk(ProtoCodec.encodeList(latest))
                enqueueOk(ProtoCodec.encodeList(oldest))
            }
            val repository = MessageRepository(rpc, cache)
            assertEquals(oldest, repository.queryHistory(CHAT, 1, 10).getOrThrow())
            assertEquals(latest, repository.queryHistory(CHAT, 0, 10).getOrThrow())
            assertEquals(oldest, repository.queryHistory(CHAT, 1, 10).getOrThrow())
            assertEquals(3, rpc.calls.size)
            listOf(1L, 0L, 1L).zip(rpc.calls).forEach { (cursor, call) ->
                assertEquals(MessageRpcContract.M_GET_HISTORY, call.second)
                assertContentEquals(MessageRpcContract.encodeGetHistory(CHAT, cursor, 10), call.third)
            }
            assertTrue(cache.getMessages(CHAT).isEmpty())
            val pager = cache.pager(CHAT)
            try { assertTrue(pager.messages.first().isEmpty()) } finally { pager.close() }
        }
    }

    @Test
    fun `query started before a UI window cannot replace its newest page or realtime edits and revocation`() = runTest {
        withCache { cache ->
            val original = message(1)
            cache.insertMessage(original)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val responses = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encodeList(listOf(message(11), original)))
            }
            var calls = 0
            val rpc = object : RpcInvoker {
                override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                    if (calls++ == 0) {
                        entered.complete(Unit)
                        release.await()
                        return ResponsePayload(1, 0, ProtoCodec.encodeList(listOf(message(2), original)))
                    }
                    return responses.invoke(service, methodId, payload)
                }
            }
            val repository = MessageRepository(rpc, cache)
            val query = async { repository.queryHistory(CHAT, 2, 10).getOrThrow() }
            entered.await()
            val pager = cache.pager(CHAT)
            try {
                repository.getHistory(CHAT, 0, 10).getOrThrow()
                val edited = message(11).copy(flags = Message.FLAG_EDITED, body = buildRichTextBody("edited"))
                val revoked = original.copy(flags = Message.FLAG_REVOKED)
                cache.insertMessage(edited)
                cache.insertMessage(revoked)
                val durable = cache.getMessages(CHAT)
                val visible = pager.messages.first()
                release.complete(Unit)
                assertEquals(listOf(message(2), original), query.await())
                assertEquals(durable, cache.getMessages(CHAT))
                assertEquals(visible, pager.messages.first())
                assertEquals(listOf(edited, revoked), durable)
            } finally { pager.close() }
        }
    }

    @Test
    fun `query rejects invalid arguments and response windows without changing SQLite`() = runTest {
        withCache { cache ->
            val existing = message(10)
            cache.insertMessage(existing)
            val rpc = FakeRpcInvoker()
            val repository = MessageRepository(rpc, cache)
            assertIs<Outcome.Failure>(repository.queryHistory(CHAT, -1, 10))
            assertIs<Outcome.Failure>(repository.queryHistory(CHAT, 1, 0))
            assertTrue(rpc.calls.isEmpty())
            val invalidPages = listOf(
                listOf(message(2)), // 超出包含起点 1 的窗口。
                listOf(message(1).copy(chatId = "other")),
                listOf(message(1), message(1)),
            )
            invalidPages.forEach { page ->
                rpc.enqueueOk(ProtoCodec.encodeList(page))
                assertIs<Outcome.Failure>(repository.queryHistory(CHAT, 1, 10))
                assertEquals(listOf(existing), cache.getMessages(CHAT))
            }
        }
    }

    private suspend fun withCache(block: suspend (LocalCacheImpl) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try { block(cache) } finally { cache.close() }
    }

    private fun message(seq: Long) = Message(
        chatId = CHAT, clientMsgId = "message-$seq", serverSeq = seq,
        senderUid = "sender", messageType = 1, timestamp = seq, body = buildRichTextBody("message $seq"),
    )

    private companion object {
        const val CHAT = "history-query"
    }
}
