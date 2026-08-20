package com.virjar.tk.bot

import com.virjar.tk.client.UserSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImBotSessionIsolationTest {
    @Test
    fun `each bot resolves only its own access token`() {
        val first = UserSession().apply {
            onAuthSuccess("uid-a", "a", "A", "refresh-a", "access-a")
        }
        val second = UserSession().apply {
            onAuthSuccess("uid-b", "b", "B", "refresh-b", "access-b")
        }

        assertEquals("access-a", requireImBotAccessToken(first))
        assertEquals("access-b", requireImBotAccessToken(second))

        first.onAuthFailed("closed")
        assertFailsWith<IllegalArgumentException> { requireImBotAccessToken(first) }
        assertEquals("access-b", requireImBotAccessToken(second))
    }
}
