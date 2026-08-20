package com.virjar.tk

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidGroupBotRouteTest {
    @Test
    fun `group bot route keeps group identity`() {
        assertEquals("group_bots/chat-123", Routes.groupBots("chat-123"))
    }
}
