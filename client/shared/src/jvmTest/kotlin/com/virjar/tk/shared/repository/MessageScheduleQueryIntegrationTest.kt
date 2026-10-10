package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.ScheduledMessage
import com.virjar.tk.protocol.rpc.gen.MessageRpcContract
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCacheImpl
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 定时发送 SDK：契约方法/载荷、身份校验、列表透传。 */
class MessageScheduleQueryIntegrationTest {
    @Test
    fun `schedule sends draft contract and returns authoritative record`() = runTest {
        withCache { cache ->
            val deliverAt = System.currentTimeMillis() + 60_000
            val record = ScheduledMessage(
                scheduledId = "sch-1",
                chatId = CHAT,
                senderUid = "me",
                messageType = MessageType.RICH_TEXT.code,
                deliverAt = deliverAt,
                status = 0,
                createdAt = 1L,
                operationId = "op-1",
                body = buildRichTextBody("定时"),
            )
            val rpc = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encode(record))
            }
            val repository = MessageRepository(rpc, cache)
            val result = repository.scheduleMessage(
                CHAT,
                MessageType.RICH_TEXT.code,
                buildRichTextBody("定时"),
                deliverAt,
                "op-1",
            ).getOrThrow()
            assertEquals(record, result)
            val call = rpc.calls.single()
            assertEquals(MessageRpcContract.M_SCHEDULE_MESSAGE, call.second)
        }
    }

    @Test
    fun `schedule rejects past deliver time and mismatched response chat`() = runTest {
        withCache { cache ->
            val rpc = FakeRpcInvoker().apply {
                enqueueOk(
                    ProtoCodec.encode(
                        ScheduledMessage(
                            "sch-1", "other-chat", "me", MessageType.RICH_TEXT.code,
                            System.currentTimeMillis() + 60_000, 0, 1L, "op-1", buildRichTextBody("x"),
                        ),
                    ),
                )
            }
            val repository = MessageRepository(rpc, cache)
            val past = System.currentTimeMillis() - 1_000
            assertTrue(repository.scheduleMessage(CHAT, 1, buildRichTextBody("x"), past, "op") is Outcome.Failure)
            assertTrue(repository.scheduleMessage(CHAT, 1, buildRichTextBody("x"), System.currentTimeMillis() + 60_000, "op") is Outcome.Failure)
        }
    }

    @Test
    fun `cancel and list pass through with identity validation`() = runTest {
        withCache { cache ->
            val list = listOf(
                ScheduledMessage(
                    "sch-2", CHAT, "me", MessageType.RICH_TEXT.code,
                    System.currentTimeMillis() + 60_000, 3, 1L, "op-2", buildRichTextBody("failed"),
                ),
            )
            val rpc = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encodePayload { writeBoolean(true) })
                enqueueOk(ProtoCodec.encodeList(list))
            }
            val repository = MessageRepository(rpc, cache)
            assertTrue(repository.cancelScheduledMessage("sch-2", "op-2").getOrThrow())
            assertEquals(list, repository.listScheduledMessages(CHAT).getOrThrow())
            assertEquals(MessageRpcContract.M_CANCEL_SCHEDULED_MESSAGE, rpc.calls[0].second)
            assertEquals(MessageRpcContract.M_LIST_SCHEDULED_MESSAGES, rpc.calls[1].second)
            // 外来 chatId 的列表条目被拒绝
            rpc.enqueueOk(
                ProtoCodec.encodeList(
                    listOf(
                        ScheduledMessage(
                            "sch-3", "elsewhere", "me", 1,
                            System.currentTimeMillis() + 60_000, 0, 1L, "op-3", buildRichTextBody("x"),
                        ),
                    ),
                ),
            )
            assertTrue(repository.listScheduledMessages(CHAT) is Outcome.Failure)
        }
    }

    private suspend fun withCache(block: suspend (LocalCacheImpl) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try {
            kotlinx.coroutines.withTimeout(5_000) { block(cache) }
        } finally {
            cache.close()
        }
    }

    private companion object {
        const val CHAT = "schedule-sdk"
    }
}
