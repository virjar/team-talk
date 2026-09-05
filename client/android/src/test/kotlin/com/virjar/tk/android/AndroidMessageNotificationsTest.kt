package com.virjar.tk.android

import com.virjar.tk.protocol.model.Conversation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidMessageNotificationsTest {
    @Test
    fun `cold snapshot and a new online baseline do not notify historical unread`() {
        val historical = conversation(unread = 8, lastSeq = 20)
        val tracker = AndroidUnreadNotificationTracker()
        assertTrue(tracker.update(listOf(historical), foreground = false).isEmpty())
        val incoming = historical.copy(unreadCount = 9, lastSeq = 21)
        assertEquals(listOf(incoming), tracker.update(listOf(incoming), foreground = false))
        assertTrue(tracker.update(listOf(incoming), foreground = false).isEmpty())

        // 重连认证完成后重新建基线，同步期间补齐的消息只更新缓存。
        val reconnected = AndroidUnreadNotificationTracker()
        assertTrue(reconnected.update(listOf(incoming.copy(unreadCount = 15, lastSeq = 27)), false).isEmpty())
    }

    @Test
    fun `foreground muted and metadata-only updates do not generate delayed notifications`() {
        val tracker = AndroidUnreadNotificationTracker()
        tracker.update(emptyList(), foreground = true)
        val first = conversation(unread = 1, lastSeq = 1)
        assertTrue(tracker.update(listOf(first), foreground = true).isEmpty())
        assertTrue(tracker.update(listOf(first), foreground = false).isEmpty())
        val muted = first.copy(unreadCount = 2, lastSeq = 2, isMuted = true)
        assertTrue(tracker.update(listOf(muted), foreground = false).isEmpty())
        val unmuted = muted.copy(isMuted = false)
        assertTrue(tracker.update(listOf(unmuted), foreground = false).isEmpty())
        val metadataOnly = unmuted.copy(unreadCount = 3)
        assertTrue(tracker.update(listOf(metadataOnly), foreground = false).isEmpty())
        val next = metadataOnly.copy(unreadCount = 4, lastSeq = 3)
        assertEquals(listOf(next), tracker.update(listOf(next), foreground = false))
        tracker.update(listOf(next.copy(unreadCount = 0)), foreground = true)
        assertTrue(tracker.update(emptyList(), foreground = false).isEmpty())
    }

    @Test
    fun `notification target belongs to one deployment dataset and account`() {
        val target = AndroidNotificationTarget("deployment-a", "dataset-a", "user-a", "chat-a")
        assertTrue(target.belongsTo("deployment-a", "dataset-a", "user-a"))
        assertFalse(target.belongsTo("deployment-b", "dataset-a", "user-a"))
        assertFalse(target.belongsTo("deployment-a", "dataset-b", "user-a"))
        assertFalse(target.belongsTo("deployment-a", "dataset-a", "user-b"))
    }

    private fun conversation(unread: Int, lastSeq: Long) = Conversation(
        chatId = "chat-a",
        chatType = 1,
        chatName = "测试会话",
        unreadCount = unread,
        lastSeq = lastSeq,
    )
}
