package com.virjar.tk.app.viewmodel

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 聊天记录扫描器与筛选谓词回归：倒序分页衔接、关键词/发送人/时间过滤、
 * 扫描预算与加载更多、按时间范围提前停止、失败重试。
 */
class ChatHistoryScanControllerTest {

    private fun message(
        seq: Long,
        timestamp: Long = seq * 1_000L,
        senderUid: String = "u1",
        text: String = "m$seq",
        messageType: Int = MessageType.RICH_TEXT.code,
        flags: Int = 0,
        link: Boolean = false,
    ): Message {
        val content = if (link) "$text https://example.com/a" else text
        return Message(
            chatId = "chat-1",
            clientMsgId = "m$seq",
            serverSeq = seq,
            senderUid = senderUid,
            messageType = messageType,
            timestamp = timestamp,
            flags = flags,
            body = RichTextBody(content, plainText = content),
        )
    }

    /** 模拟服务端 getHistory：fromSeq=0 取最新页，否则取 seq<=fromSeq 的更早一页，均倒序。 */
    private fun fakeSource(historyDesc: List<Message>): suspend (Long, Int) -> List<Message> =
        { fromSeq, limit ->
            val source = if (fromSeq == 0L) historyDesc else historyDesc.filter { it.serverSeq <= fromSeq }
            source.take(limit)
        }

    @Test
    fun `scan filters by keyword and keeps newest-first order across pages`() = runTest {
        val historyDesc = (1L..25L).map(::message).reversed()
        val controller = ChatHistoryScanController(this, fakeSource(historyDesc))

        controller.search(chatHistoryPredicate(ChatHistoryCategory.CHAT_RECORDS, "m2", emptySet(), null))
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse(state.failed)
        assertTrue(state.exhausted)
        // 命中 m25..m20 与 m2（"m21" 不含 "m2"？包含——含 m21、m20 在内共 7 条），断言精确序列。
        assertEquals(listOf(25L, 24L, 23L, 22L, 21L, 20L, 2L), state.results.map(Message::serverSeq))
        assertEquals(25L, state.scannedCount)
    }

    @Test
    fun `stopWhen stops at range boundary without scanning older history`() = runTest {
        val historyDesc = (1L..50L).map { message(it, timestamp = it * 10_000L) }.reversed()
        val controller = ChatHistoryScanController(this, fakeSource(historyDesc))

        // 只看 seq>=20 对应的时间范围：倒序扫到 seq19（时间小于起点）即停。
        controller.search(
            predicate = { it.serverSeq >= 20L },
            stopWhen = { it.timestamp < 19 * 10_000L },
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state.exhausted)
        assertEquals((50L downTo 20L).toList(), state.results.map(Message::serverSeq))
        assertTrue(state.scannedCount < 50L)
    }

    @Test
    fun `loadMore extends scan budget until history exhausted`() = runTest {
        val historyDesc = (1L..1000L).map(::message).reversed()
        val controller = ChatHistoryScanController(this, fakeSource(historyDesc))

        controller.search(chatHistoryPredicate(ChatHistoryCategory.CHAT_RECORDS, "", emptySet(), null))
        advanceUntilIdle()
        assertEquals(300, controller.state.value.results.size)
        // 预算用尽但历史未到底：可继续加载。
        assertFalse(controller.state.value.exhausted)
        assertTrue(controller.state.value.budgetReached)

        controller.loadMore()
        advanceUntilIdle()
        assertEquals(600, controller.state.value.results.size)

        controller.loadMore()
        advanceUntilIdle()
        controller.loadMore()
        advanceUntilIdle()
        val final = controller.state.value
        // 最后一批扫到首条消息，真正到界。
        assertTrue(final.exhausted)
        assertFalse(final.budgetReached)
        assertEquals(1000, final.results.size)
    }

    @Test
    fun `failure marks failed and retry recovers`() = runTest {
        var shouldFail = true
        val historyDesc = (1L..5L).map(::message).reversed()
        val controller = ChatHistoryScanController(this) { _, _ ->
            if (shouldFail) throw IllegalStateException("network down")
            fakeSource(historyDesc)(0L, ChatHistoryScanController.PAGE_SIZE)
        }

        controller.search(chatHistoryPredicate(ChatHistoryCategory.CHAT_RECORDS, "", emptySet(), null))
        advanceUntilIdle()
        assertTrue(controller.state.value.failed)
        assertTrue(controller.state.value.results.isEmpty())

        shouldFail = false
        controller.retry()
        advanceUntilIdle()
        val state = controller.state.value
        assertFalse(state.failed)
        assertEquals(5, state.results.size)
    }

    @Test
    fun `category predicate separates types and skips revoked messages`() {
        val text = message(1L)
        val voice = message(2L, messageType = MessageType.VOICE.code)
        val image = message(3L, messageType = MessageType.IMAGE.code)
        val file = message(4L, messageType = MessageType.FILE.code)
        val link = message(5L, link = true)
        val revoked = message(6L, text = "m1", flags = Message.FLAG_REVOKED)

        assertTrue(messageInHistoryCategory(text, ChatHistoryCategory.CHAT_RECORDS))
        assertTrue(messageInHistoryCategory(voice, ChatHistoryCategory.CHAT_RECORDS))
        assertTrue(messageInHistoryCategory(image, ChatHistoryCategory.MEDIA))
        assertTrue(messageInHistoryCategory(file, ChatHistoryCategory.FILES))
        assertTrue(messageInHistoryCategory(link, ChatHistoryCategory.LINKS))
        assertFalse(messageInHistoryCategory(image, ChatHistoryCategory.CHAT_RECORDS))
        // 已撤回消息不进入任何分类，即使正文与未撤回消息相同。
        assertFalse(messageInHistoryCategory(revoked, ChatHistoryCategory.CHAT_RECORDS))
    }

    @Test
    fun `link extraction only accepts rich text and reply bodies`() {
        assertEquals("https://example.com/a", messageHistoryLink(message(1L, link = true)))
        assertNull(messageHistoryLink(message(2L)))
        assertNull(messageHistoryLink(message(3L, messageType = MessageType.FILE.code, link = true)))
    }

    @Test
    fun `date range converts picker UTC days to local day bounds`() {
        val zone = TimeZone.of("Asia/Shanghai")
        val startUtc = Instant.parse("2026-09-20T00:00:00Z").toEpochMilliseconds()
        val endUtc = Instant.parse("2026-09-21T00:00:00Z").toEpochMilliseconds()

        val range = chatHistoryDateRangeFromPicker(startUtc, endUtc, zone)
        // 2026-09-20 08:00 +08:00（本地 20 日零点）到 2026-09-22 00:00 +08:00（21 日次日零点）。
        assertEquals(Instant.parse("2026-09-19T16:00:00Z").toEpochMilliseconds(), range.startMillis)
        assertEquals(Instant.parse("2026-09-21T16:00:00Z").toEpochMilliseconds(), range.endExclusiveMillis)
        assertTrue(range.contains(Instant.parse("2026-09-20T02:00:00Z").toEpochMilliseconds()))
        assertFalse(range.contains(Instant.parse("2026-09-21T16:00:00Z").toEpochMilliseconds()))
    }
}
