package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.rpc.gen.MessageRpcContract
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.MessageRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 线程查询与本地线程聚合：独立查询不写缓存，聚合只统计根仍在窗内的线程。 */
class MessageThreadQueryIntegrationTest {
    @Test
    fun `thread query uses its own contract method and never writes the cache`() = runTest {
        withCache { cache ->
            val thread = listOf(reply(4, 2), reply(2, 1))
            val rpc = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encodeList(thread))
            }
            val repository = MessageRepository(rpc, cache)
            assertEquals(thread, repository.queryThreadMessages(CHAT, ROOT, 0, 10).getOrThrow())
            val call = rpc.calls.single()
            assertEquals(MessageRpcContract.M_GET_THREAD_MESSAGES, call.second)
            assertContentEquals(MessageRpcContract.encodeGetThreadMessages(CHAT, ROOT, 0, 10), call.third)
            assertTrue(cache.getMessages(CHAT).isEmpty())
            val pager = cache.pager(CHAT)
            try { assertTrue(pager.messages.first().isEmpty()) } finally { pager.close() }
        }
    }

    @Test
    fun `thread query rejects pages containing the root or non-reply messages`() = runTest {
        withCache { cache ->
            val rpc = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encodeList(listOf(reply(4, 2), rich(3))))
                enqueueOk(ProtoCodec.encodeList(listOf(message(ROOT))))
            }
            val repository = MessageRepository(rpc, cache)
            // outcome 把校验失败包装为 Failure；两条畸形响应分别命中非回复与根自身两种拒绝
            assertTrue(repository.queryThreadMessages(CHAT, ROOT, 0, 10) is Outcome.Failure)
            assertTrue(repository.queryThreadMessages(CHAT, ROOT, 0, 10) is Outcome.Failure)
        }
    }

    @Test
    fun `local thread counts aggregate nested replies and skip threads whose root left the window`() = runTest {
        withCache { cache ->
            // 1=根；2=回复根；3=无关；4=对回复的回复（同根）；5=断链回复
            listOf(message(1), reply(2, 1), rich(3), reply(4, 2), reply(5, 999)).forEach(cache::insertMessage)
            // 断链回复（目标 999 缺失）按设计跳过：根 1 计 2 条（直接回复 + 嵌套回复）
            assertEquals(mapOf(1L to 2), cache.threadReplyCounts(CHAT))
            // 窗口收窄到最新 4 条（2..5）：根 1 离开窗口，全部回复不再计入
            assertEquals(emptyMap(), cache.threadReplyCounts(CHAT, scanLimit = 4))
            assertTrue(cache.threadReplyCounts("other-chat").isEmpty())
        }
    }

    private fun rich(seq: Long) = message(seq)

    private fun reply(seq: Long, targetSeq: Long) = Message(
        chatId = CHAT,
        clientMsgId = "reply-$seq",
        serverSeq = seq,
        senderUid = "sender",
        messageType = MessageType.REPLY.code,
        timestamp = seq,
        body = ReplyBody(replyToMsgId = targetSeq.toString(), replyToSenderUid = "sender", content = "reply $seq"),
    )

    private fun message(seq: Long) = Message(
        chatId = CHAT,
        clientMsgId = "message-$seq",
        serverSeq = seq,
        senderUid = "sender",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = seq,
        body = buildRichTextBody("message $seq"),
    )

    private suspend fun withCache(block: suspend (LocalCacheImpl) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try {
            withTimeout(5_000) { block(cache) }
        } finally {
            cache.close()
        }
    }

    private companion object {
        const val CHAT = "thread-query"
        const val ROOT = 1L
    }
}
