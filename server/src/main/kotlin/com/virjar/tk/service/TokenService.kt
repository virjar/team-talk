package com.virjar.tk.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.*

object TokenService {
    private lateinit var algorithm: Algorithm
    private val accessExpireHours = 24L
    private val refreshExpireDays = 30L

    fun init(secret: String) {
        algorithm = Algorithm.HMAC256(secret)
    }

    data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
    )

    fun generateTokenPair(uid: String): TokenPair {
        val now = Date()
        val accessExpiry = Date(now.time + accessExpireHours * 3600_000)

        val accessToken = JWT.create()
            .withSubject(uid)
            .withIssuedAt(now)
            .withExpiresAt(accessExpiry)
            .sign(algorithm)

        val refreshToken = UUID.randomUUID().toString().replace("-", "")
        val expiresIn = accessExpireHours * 3600

        return TokenPair(accessToken, refreshToken, expiresIn)
    }

    fun validateAccessToken(token: String): String? {
        return try {
            val decoded = JWT.require(algorithm).build().verify(token)
            decoded.subject
        } catch (_: Exception) {
            null
        }
    }

    fun getRefreshTokenExpiry(): Long {
        return System.currentTimeMillis() + refreshExpireDays * 24 * 3600_000
    }
}
