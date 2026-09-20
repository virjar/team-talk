package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.MessageReactionEventPayload
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.shared.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会话级本地标记回归：本机「标为未读」叠加与清除、本机清空聊天记录的水位语义
 * （清空后历史拉取/迟到事件不得把已清空消息重新落库，新消息不受影响）。
 */
class ChatHistoryClearIntegrationTest {

    private fun newCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun message(chatId: String, seq: Long, id: String = "m$seq") = Message(
        chatId = chatId,
        clientMsgId = id,
        serverSeq = seq,
        senderUid = "sender",
        messageType = 1,
        timestamp = seq,
    )

    /** 默认 readSeq = lastSeq（全部已读，派生未读为 0），匹配「标为未读」的真实场景。 */
    private fun conversation(
        chatId: String,
        lastSeq: Long,
        lastMessage: String? = null,
        readSeq: Long = lastSeq,
    ) = Conversation(
        chatId = chatId,
        chatType = 1,
        lastMessage = lastMessage,
        lastMsgTimestamp = lastSeq,
        lastSeq = lastSeq,
        readSeq = readSeq,
    )

    @Test
    fun `clear removes local history and suppresses stale preview`() = runTest {
        val cache = newCache()
        val chatId = "clear-preview"
        cache.upsertConversation(conversation(chatId, lastSeq = 3, lastMessage = "旧摘要"))
        cache.insertMessage(message(chatId, 1L))
        cache.insertMessage(message(chatId, 2L))
        cache.insertMessage(message(chatId, 3L))
        cache.applyMessageReactionDelta(
            MessageReactionEventPayload(chatId, 2L, "👍", "u1", action = 1),
        )
        assertEquals(3, cache.getMessages(chatId).size)

        cache.clearChatHistory(chatId)

        assertEquals(emptyList(), cache.getMessages(chatId))
        assertEquals(emptyMap(), cache.observeMessageReactions(chatId).first())
        // 会话身份保留：lastSeq / 已读水位语义不变，摘要被水位抑制。
        val published = cache.getConversations().single { it.chatId == chatId }
        assertEquals(3L, published.lastSeq)
        assertNull(published.lastMessage)

        // 清空水位之前的历史页与迟到事件都不得复活已清空消息。
        val lease = cache.beginMessageHistoryLease(chatId, resetResidentWindow = true)
        assertTrue(cache.applyMessageHistoryPage(lease, listOf(message(chatId, 2L), message(chatId, 3L))))
        assertEquals(emptyList(), cache.getMessages(chatId))
        cache.insertMessage(message(chatId, 1L))
        assertEquals(emptyList(), cache.getMessages(chatId))

        // 新消息（seq > 水位）正常进入投影，摘要恢复展示。
        cache.insertMessage(message(chatId, 4L, "fresh"))
        assertEquals(listOf("fresh"), cache.getMessages(chatId).map(Message::clientMsgId))
        cache.upsertConversation(conversation(chatId, lastSeq = 4, lastMessage = "新消息", readSeq = 3))
        assertEquals("新消息", cache.getConversations().single { it.chatId == chatId }.lastMessage)
    }

    @Test
    fun `clear watermark and manual unread survive cache reopen`() = runTest {
        val directory = createTempDirectory("chat-clear-reopen")
        val database = directory.resolve("cache.sqlite").toFile()
        val chatId = "reopen-chat"

        val first = open(database, createSchema = true)
        try {
            first.upsertConversation(conversation(chatId, lastSeq = 3, lastMessage = "旧摘要"))
            first.insertMessage(message(chatId, 3L))
            first.clearChatHistory(chatId)
            first.setConversationMarkedUnread(chatId, true)
            assertEquals(1, first.getConversations().single { it.chatId == chatId }.unreadCount)
        } finally {
            first.close()
        }

        val reopened = open(database)
        try {
            assertEquals(emptyList(), reopened.getMessages(chatId))
            assertEquals(1, reopened.getConversations().single { it.chatId == chatId }.unreadCount)

            // 重启后的历史拉取同样被水位过滤；进行中的本地读取清除手动未读。
            val lease = reopened.beginMessageHistoryLease(chatId, resetResidentWindow = true)
            assertTrue(reopened.applyMessageHistoryPage(lease, listOf(message(chatId, 1L))))
            assertEquals(emptyList(), reopened.getMessages(chatId))
            reopened.enqueueConversationRead(chatId, 3L)
            assertEquals(0, reopened.getConversations().single { it.chatId == chatId }.unreadCount)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `manual unread overlays derived count and merges keep it`() = runTest {
        val cache = newCache()
        val chatId = "manual-unread"
        cache.upsertConversation(conversation(chatId, lastSeq = 2))

        cache.setConversationMarkedUnread(chatId, true)
        assertEquals(1, cache.getConversations().single { it.chatId == chatId }.unreadCount)

        // 服务端快照合并重算派生未读后，本机标记继续叠加。
        cache.upsertConversation(conversation(chatId, lastSeq = 2))
        assertEquals(1, cache.getConversations().single { it.chatId == chatId }.unreadCount)

        // 幂等：重复设置不放大徽标。
        cache.setConversationMarkedUnread(chatId, true)
        assertEquals(1, cache.getConversations().single { it.chatId == chatId }.unreadCount)

        // 有真实未读时显示真实计数；读取推进清除本机标记。
        cache.upsertConversation(conversation(chatId, lastSeq = 5).copy(readSeq = 2))
        assertEquals(3, cache.getConversations().single { it.chatId == chatId }.unreadCount)
        cache.enqueueConversationRead(chatId, 5L)
        assertEquals(0, cache.getConversations().single { it.chatId == chatId }.unreadCount)

        cache.setConversationMarkedUnread(chatId, true)
        cache.setConversationMarkedUnread(chatId, false)
        assertEquals(0, cache.getConversations().single { it.chatId == chatId }.unreadCount)
    }

    private fun open(database: java.io.File, createSchema: Boolean = false): LocalCacheImpl {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
        if (createSchema) AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }
}
