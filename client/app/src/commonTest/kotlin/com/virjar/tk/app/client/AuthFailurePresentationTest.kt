package com.virjar.tk.app.client

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthFailurePresentationTest {
    @Test
    fun `expired legacy credentials have a localized actionable explanation`() {
        assertEquals("登录已失效，请重新登录", authenticationFailureMessage("Invalid or expired refresh token"))
        assertEquals("账号已被封禁", authenticationFailureMessage("账号已被封禁"))
    }
}
