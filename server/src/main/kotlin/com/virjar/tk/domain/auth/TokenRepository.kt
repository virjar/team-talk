package com.virjar.tk.domain.auth

/** Authentication-token persistence port. */
interface TokenRepository {
    fun generateTokens(uid: String, deviceId: String, deviceFlag: Int): Pair<String, String>
    fun validateAccessToken(token: String): TokenInfo?
    fun refreshAccessToken(refreshToken: String): Pair<String, String>?
    fun revokeRefreshToken(refreshToken: String): Boolean
    fun revokeAllDeviceTokens(uid: String, deviceId: String)
    fun revokeAllUserTokens(uid: String)
}

data class TokenInfo(
    val uid: String,
    val deviceId: String,
    val deviceFlag: Int,
    val createdAt: Long,
    val expiresAt: Long,
)
