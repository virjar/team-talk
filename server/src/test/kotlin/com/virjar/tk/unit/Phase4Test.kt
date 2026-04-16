package com.virjar.tk.unit

import com.virjar.tk.service.ChannelService
import com.virjar.tk.service.TokenService
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class Phase4Test {

    @Test
    fun `personal channel id is deterministic`() {
        val id1 = ChannelService.buildPersonalChannelId("aaa", "bbb")
        val id2 = ChannelService.buildPersonalChannelId("bbb", "aaa")
        assertEquals(id1, id2)
        assertTrue(id1.startsWith("p:"))
    }

    @Test
    fun `different user pairs produce different channel ids`() {
        val id1 = ChannelService.buildPersonalChannelId("aaa", "bbb")
        val id2 = ChannelService.buildPersonalChannelId("aaa", "ccc")
        assertFalse(id1 == id2)
    }

    @Test
    fun `token with different secrets are rejected`() {
        TokenService.init("SecretAlpha2026abcdefghijklmnopqrstuvwxyz")
        val pair = TokenService.generateTokenPair("user1")
        TokenService.init("SecretBeta2026abcdefghijklmnopqrstuvwxyz")
        val result = TokenService.validateAccessToken(pair.accessToken)
        assertEquals(null, result)
    }

    @Test
    fun `token round trip works`() {
        TokenService.init("MyTestSecretKeyForJWT2026abcdefghij")
        val pair = TokenService.generateTokenPair("user42")
        assertEquals("user42", TokenService.validateAccessToken(pair.accessToken))
        assertTrue(pair.expiresIn > 0)
    }

    @Test
    fun `token service rejects garbage input`() {
        TokenService.init("MyTestSecretKeyForJWT2026abcdefghij")
        assertEquals(null, TokenService.validateAccessToken(""))
        assertEquals(null, TokenService.validateAccessToken("not.a.token"))
        assertEquals(null, TokenService.validateAccessToken("eyJhbGciOiJIUzI1NiJ9.invalid.signature"))
    }
}
