package com.virjar.tk.domain.auth

/** Narrow bearer-validation boundary used by HTTP routes. Implementations must fail closed. */
fun interface AccessTokenValidator {
    suspend fun validateAccessToken(token: String): TokenInfo?
}

data class CredentialDevice(
    val deviceId: String,
    val deviceName: String?,
    val deviceModel: String?,
    val deviceFlag: Int,
)

/** A password was verified against exactly this user credential epoch. */
class CredentialIssueRequest(
    val uid: String,
    val expectedUserCredentialEpoch: Long,
    val expectedPasswordHash: String,
    val device: CredentialDevice,
) {
    override fun toString(): String =
        "CredentialIssueRequest(uid=$uid, expectedUserCredentialEpoch=$expectedUserCredentialEpoch, " +
            "expectedPasswordHash=<redacted>, device=$device)"
}

/** Raw credentials are intentionally redacted from logs and debugger string rendering. */
data class CredentialSubject(
    val username: String,
    val name: String,
)

class IssuedCredentials(
    val accessToken: String,
    val refreshToken: String,
    val principal: TokenInfo,
    val subject: CredentialSubject,
) {
    override fun toString(): String =
        "IssuedCredentials(<redacted>, principal=$principal, subject=<redacted>)"
}

/** Authentication credential persistence. Raw tokens must never be stored. */
interface TokenRepository : AccessTokenValidator {
    suspend fun issueCredentials(request: CredentialIssueRequest): IssuedCredentials?

    suspend fun refreshCredentials(
        refreshToken: String,
        device: CredentialDevice,
    ): IssuedCredentials?

    /**
     * Revoke one device and advance its irreversible credential epoch. Callers must derive
     * [uid] and [deviceId] from an authenticated session or an authorized administrator command.
     *
     * @return the committed device epoch, or null when the device does not exist.
     */
    suspend fun revokeDevice(uid: String, deviceId: String): Long?

    /** Atomically compare the verified old hash, replace it, and revoke every user credential. */
    suspend fun changePasswordAndRevoke(
        uid: String,
        expectedPasswordHash: String,
        newPasswordHash: String,
    ): Long?
}

/** Administrative credential mutations that commit before any live-session fence is published. */
interface CredentialAdministration {
    suspend fun banUser(uid: String): Long
    suspend fun unbanUser(uid: String)
    suspend fun resetPasswordAndRevoke(uid: String, passwordHash: String): Long
}

data class TokenInfo(
    val uid: String,
    val deviceId: String,
    val deviceFlag: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val userCredentialEpoch: Long,
    val deviceCredentialEpoch: Long,
)
