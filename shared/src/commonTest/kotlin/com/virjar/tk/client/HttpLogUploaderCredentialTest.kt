package com.virjar.tk.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpLogUploaderCredentialTest {
    @Test
    fun `same owner accepts rotated token`() {
        assertEquals(
            "token-a2",
            ownedHttpAccessToken("uid-a", SessionHttpCredentials("uid-a", "token-a2")),
        )
    }

    @Test
    fun `retired owner cannot borrow another account token`() {
        assertFailsWith<IllegalStateException> {
            ownedHttpAccessToken("uid-a", SessionHttpCredentials("uid-b", "token-b"))
        }
    }

    @Test
    fun `missing token fails closed`() {
        assertFailsWith<IllegalStateException> {
            ownedHttpAccessToken("uid-a", SessionHttpCredentials("uid-a", null))
        }
    }
}
