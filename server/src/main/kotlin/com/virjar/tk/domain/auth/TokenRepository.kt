package com.virjar.tk.domain.auth

/** Authentication-token persistence port. */
interface TokenRepository {
    fun generateTokens(uid: String, deviceId: String, deviceFlag: Int): Pair<String, String>
    fun validateAccessToken(token: String): TokenInfo?
    /**
     * Rotate [refreshToken] only when it belongs to the device declared by the reconnecting
     * client. The stored token identity is authoritative; a bearer must not be able to relabel an
     * existing session as another device in the online registry.
     */
    fun refreshAccessToken(
        refreshToken: String,
        expectedDeviceId: String,
        expectedDeviceFlag: Int,
    ): Pair<String, String>?

    /** Revoke a refresh token only when it belongs to the authenticated RPC principal/device. */
    fun revokeRefreshToken(
        refreshToken: String,
        expectedUid: String? = null,
        expectedDeviceId: String? = null,
    ): Boolean
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
