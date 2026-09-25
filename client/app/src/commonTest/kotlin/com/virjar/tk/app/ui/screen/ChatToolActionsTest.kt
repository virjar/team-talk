package com.virjar.tk.app.ui.screen

import com.virjar.tk.protocol.model.ChatType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 会话工具动作清单按会话类型收敛：系统账号与群聊不提供"发起群聊"。 */
class ChatToolActionsTest {
    @Test
    fun `personal chat with human peer offers create group`() {
        val actions = chatToolActionsFor(ChatType.PERSONAL, peerUid = "Bq2xK9wQ")
        assertEquals(
            listOf(ChatToolActionKind.HISTORY_SEARCH, ChatToolActionKind.CREATE_GROUP, ChatToolActionKind.CLEAR_HISTORY),
            actions,
        )
    }

    @Test
    fun `service account chat never offers create group`() {
        listOf("sys_service", "sys_assistant").forEach { uid ->
            val actions = chatToolActionsFor(ChatType.PERSONAL, peerUid = uid)
            assertFalse(actions.contains(ChatToolActionKind.CREATE_GROUP), uid)
            assertTrue(actions.contains(ChatToolActionKind.HISTORY_SEARCH), uid)
        }
    }

    @Test
    fun `group chat never offers create group`() {
        val actions = chatToolActionsFor(ChatType.GROUP, peerUid = null)
        assertEquals(
            listOf(ChatToolActionKind.HISTORY_SEARCH, ChatToolActionKind.CLEAR_HISTORY),
            actions,
        )
    }

    @Test
    fun `saved chat never offers create group`() {
        assertFalse(chatToolActionsFor(ChatType.SAVED, peerUid = null).contains(ChatToolActionKind.CREATE_GROUP))
    }

    @Test
    fun `system account uid recognition covers the sys prefix`() {
        assertTrue(isSystemAccountUid("sys_service"))
        assertTrue(isSystemAccountUid("sys_assistant"))
        assertTrue(isSystemAccountUid("sys_help"))
        assertFalse(isSystemAccountUid(null))
        assertFalse(isSystemAccountUid("b1qeTyFJ"))
        assertFalse(isSystemAccountUid("system-friend"))
    }
}
