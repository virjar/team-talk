package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthPayloadRedactionTest {
    @Test
    fun `auth request diagnostics never expose secrets`() {
        val rendered = AuthRequestPayload(
            authType = 0,
            username = "secret-username",
            password = "secret-password",
            refreshToken = "secret-refresh",
            deviceId = "device-1",
        ).toString()

        assertFalse(rendered.contains("secret-username"))
        assertFalse(rendered.contains("secret-password"))
        assertFalse(rendered.contains("secret-refresh"))
        assertTrue(rendered.contains("hasPassword=true"))
        assertTrue(rendered.contains("hasRefreshToken=true"))
    }

    @Test
    fun `auth response diagnostics never expose credentials`() {
        val rendered = AuthResponsePayload(
            code = AuthResponsePayload.CODE_OK,
            uid = "uid-1",
            accessToken = "secret-access",
            refreshToken = "secret-refresh",
        ).toString()

        assertFalse(rendered.contains("secret-access"))
        assertFalse(rendered.contains("secret-refresh"))
        assertTrue(rendered.contains("uid=uid-1"))
        assertTrue(rendered.contains("hasAccessToken=true"))
    }
}
