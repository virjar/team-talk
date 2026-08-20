package com.virjar.tk.bot

import com.virjar.tk.client.UserSession
import kotlin.test.Test
import kotlin.test.assertEquals

class ImBotSessionIsolationTest {
    @Test
    fun `each bot exposes only its own atomic HTTP credential generation`() {
        val first = UserSession().apply {
            onAuthSuccess("uid-a", "a", "A", "refresh-a", "access-a")
        }
        val second = UserSession().apply {
            onAuthSuccess("uid-b", "b", "B", "refresh-b", "access-b")
        }

        assertEquals("uid-a" to "access-a", first.httpCredentialsSnapshot().let { it.uid to it.accessToken })
        assertEquals("uid-b" to "access-b", second.httpCredentialsSnapshot().let { it.uid to it.accessToken })

        first.onAuthFailed("closed")
        assertEquals("" to null, first.httpCredentialsSnapshot().let { it.uid to it.accessToken })
        assertEquals("uid-b" to "access-b", second.httpCredentialsSnapshot().let { it.uid to it.accessToken })
    }
}
