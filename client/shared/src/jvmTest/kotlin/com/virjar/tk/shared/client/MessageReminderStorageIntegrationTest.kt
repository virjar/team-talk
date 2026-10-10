package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 消息稍后提醒表（MSG-01/F4）：登记/到期筛选/通知收据/取消，纯本地事实。 */
class MessageReminderStorageIntegrationTest {
    @Test
    fun `upsert due filtering receipt and cancel work across reopen`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try {
            cache.upsertMessageReminder("chat-a", "msg-1", 10, "提醒正文", 1_000)
            cache.upsertMessageReminder("chat-a", "msg-2", 11, "更晚的提醒", 3_000)
            cache.upsertMessageReminder("chat-b", "msg-3", 12, "另一个会话", 2_000)

            assertEquals(3, cache.activeMessageReminders().size)
            // 到点筛选含边界（<= now），升序
            assertEquals(
                listOf("msg-1", "msg-3"),
                cache.dueMessageReminders(2_000).map { it.clientMsgId },
            )
            // 同键 upsert 覆盖（remindAt 更新、notified 归零）
            cache.upsertMessageReminder("chat-a", "msg-1", 10, "提醒正文", 5_000)
            assertEquals(listOf("msg-3"), cache.dueMessageReminders(2_000).map { it.clientMsgId })

            // 通知收据：重启（新实例同文件）不重复弹——这里验证收据语义本身
            cache.markMessageReminderNotified("chat-a", "msg-1")
            assertTrue(cache.dueMessageReminders(5_000).none { it.clientMsgId == "msg-1" })

            // 取消删除行
            cache.cancelMessageReminder("chat-a", "msg-2")
            assertEquals(listOf("chat-b"), cache.activeMessageReminders().map { it.chatId }.distinct())
        } finally {
            cache.close()
        }
    }
}
