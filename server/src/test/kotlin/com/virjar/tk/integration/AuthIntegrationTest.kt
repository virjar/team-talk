package com.virjar.tk.integration

import com.virjar.tk.service.TokenService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mindrot.jbcrypt.BCrypt

class AuthUnitTest {
    private val secret = "TeamTalk2026SecretKeyForJWTTokenGenerationMustBeAtLeast256BitsLong"

    @BeforeEach
    fun setup() {
        TokenService.init(secret)
    }

    @Test
    fun `token generation and uid round-trip`() {
        val pair = TokenService.generateTokenPair("user123")
        assertNotNull(pair.accessToken)
        assertNotNull(pair.refreshToken)
        assertTrue(pair.expiresIn > 0)
    }

    @Test
    fun `token validation succeeds for valid token`() {
        val pair = TokenService.generateTokenPair("user123")
        val uid = TokenService.validateAccessToken(pair.accessToken)
        assertEquals("user123", uid)
    }

    @Test
    fun `token validation fails for invalid token`() {
        val uid = TokenService.validateAccessToken("invalid.token.value")
        assertNull(uid)
    }

    @Test
    fun `password hashing and verification`() {
        val password = "testPassword123"
        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        assertTrue(BCrypt.checkpw(password, hash))
        assertFalse(BCrypt.checkpw("wrongPassword", hash))
    }
}
