package com.virjar.tk.app.ui.screen

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 聊天消息瀑布流时间分隔与连续气泡分组契约测试。
 */
class ChatMessageGroupingTest {
    private val testZone = TimeZone.of("Asia/Shanghai")

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime(year, month, day, hour, minute, second).toInstant(testZone).toEpochMilliseconds()

    private fun msg(
        seq: Long,
        sender: String,
        timestamp: Long,
        flags: Int = 0,
    ): Message = Message(
        chatId = "chat-1",
        clientMsgId = "c-$seq",
        senderUid = sender,
        messageType = MessageType.RICH_TEXT.code,
        timestamp = timestamp,
        serverSeq = seq,
        flags = flags,
        body = buildRichTextBody("msg-$seq"),
    )

    @Test
    fun `first reply after long gap starts a new time block and stays stable when later replies arrive`() {
        val a = msg(1, "u1", epochMillis(2026, 11, 20, 9, 0))
        val b = msg(2, "u2", epochMillis(2026, 11, 20, 10, 0))
        val c = msg(3, "u2", epochMillis(2026, 11, 20, 10, 1))

        // 仅有 9:00 与 10:00 两条消息时（倒序存储：最新在前）
        val twoOnly = computeChatMessageGroupings(listOf(b, a), testZone)
        assertTrue(twoOnly[1].showTimeSeparator, "9:00 最早消息上方应展示时间分隔")
        assertTrue(twoOnly[0].showTimeSeparator, "10:00 回复上方应展示时间分隔")

        // 10:01 追加新消息后，10:00 的第一条消息 B 绝不能掉进 9:00 块，C 紧跟 B 且不新开时间条
        val threeMessages = computeChatMessageGroupings(listOf(c, b, a), testZone)
        assertTrue(threeMessages[2].showTimeSeparator, "A(9:00) 上方应有时间分隔")
        assertTrue(threeMessages[1].showTimeSeparator, "B(10:00) 上方必须保留时间分隔，不能归入 9:00 块")
        assertFalse(threeMessages[1].isContinuation)
        assertFalse(threeMessages[0].showTimeSeparator, "C(10:01) 与 B 相差 1 分钟，归入 10:00 块")
        assertTrue(threeMessages[0].isContinuation, "C 与同发送者 B 相差 1 分钟应合并气泡")
    }

    @Test
    fun `revoked block leader still keeps time separator and breaks continuation`() {
        val a = msg(1, "u1", epochMillis(2026, 11, 20, 9, 0))
        val bRevoked = msg(2, "u2", epochMillis(2026, 11, 20, 10, 0), flags = Message.FLAG_REVOKED)
        val c = msg(3, "u2", epochMillis(2026, 11, 20, 10, 1))

        val groupings = computeChatMessageGroupings(listOf(c, bRevoked, a), testZone)
        assertTrue(groupings[1].showTimeSeparator, "时间段首条被撤回时，该条目上方仍须保留时间分隔")
        assertFalse(groupings[1].isContinuation)
        assertFalse(groupings[0].showTimeSeparator, "紧随其后的同段消息不重复输出时间条")
        assertFalse(groupings[0].isContinuation, "前一条被撤回后，下一条气泡必须恢复头像")
    }

    @Test
    fun `cross midnight splits time block and prevents continuation within five minutes`() {
        val lateNight = msg(1, "u1", epochMillis(2026, 11, 20, 23, 58))
        val afterMidnight = msg(2, "u1", epochMillis(2026, 11, 21, 0, 1))

        val groupings = computeChatMessageGroupings(listOf(afterMidnight, lateNight), testZone)
        assertTrue(groupings[1].showTimeSeparator)
        assertTrue(groupings[0].showTimeSeparator, "跨自然日即使相差仅 3 分钟也必须切分新时间分隔")
        assertFalse(groupings[0].isContinuation, "跨自然日新时间块首条不能隐藏头像")
    }

    @Test
    fun `dense continuous messages split a new time anchor after max block span`() {
        // 每 3 分钟一条连续发 6 条（总跨度 15 分钟），09:00 为锚点，09:15 应再出新时间条
        val messagesDesc = (5 downTo 0).map { idx ->
            msg(idx + 1L, "u1", epochMillis(2026, 11, 20, 9, idx * 3))
        }
        val groupings = computeChatMessageGroupings(messagesDesc, testZone)
        // index 5 = 09:00, index 0 = 09:15
        assertEquals(
            listOf(true, false, false, false, false, true),
            groupings.reversed().map { it.showTimeSeparator },
        )
        assertFalse(groupings[0].isContinuation, "头顶出现新时间锚点时，该气泡不可再作为连续气泡隐藏头像")
    }
}
