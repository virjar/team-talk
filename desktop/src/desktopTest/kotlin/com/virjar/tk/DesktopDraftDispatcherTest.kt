package com.virjar.tk

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopDraftDispatcherTest {

    @Test
    fun `聊天页作用域取消后最终清空仍同步交给会话保存器`() {
        val calls = mutableListOf<Pair<String, String?>>()
        val dispatcher = DesktopDraftDispatcher { chatId, draft -> calls += chatId to draft }

        dispatcher.update("chat-1", "未发送草稿")
        val disposedPanelJob = Job().apply { cancel() }
        assertFalse(disposedPanelJob.isActive)
        // ChatPanel.onDispose 的回调不再通过该页面 Job launch，而是直接交给 session dispatcher。
        dispatcher.update("chat-1", "")

        assertEquals(
            listOf("chat-1" to "未发送草稿", "chat-1" to null),
            calls,
        )
    }

    @Test
    fun `同一会话草稿意图按调用顺序转发`() {
        val calls = mutableListOf<Pair<String, String?>>()
        val dispatcher = DesktopDraftDispatcher { chatId, draft -> calls += chatId to draft }

        dispatcher.update("chat-1", "第一版")
        dispatcher.update("chat-1", "第二版")
        dispatcher.update("chat-1", "")

        assertEquals(
            listOf("chat-1" to "第一版", "chat-1" to "第二版", "chat-1" to null),
            calls,
        )
    }
}
