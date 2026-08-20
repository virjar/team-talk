package com.virjar.tk.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatReadVisibilityTest {
    @Test
    fun `foreground visible confirmed message advances read target`() {
        assertEquals(42L, visibleChatReadTarget(readReceiptsEnabled = true, latestVisibleServerSeq = 42))
    }

    @Test
    fun `background or unconfirmed message cannot be consumed`() {
        assertEquals(null, visibleChatReadTarget(readReceiptsEnabled = false, latestVisibleServerSeq = 42))
        assertEquals(null, visibleChatReadTarget(readReceiptsEnabled = true, latestVisibleServerSeq = 0))
    }
}
