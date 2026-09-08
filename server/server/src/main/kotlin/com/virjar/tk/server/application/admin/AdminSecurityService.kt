package com.virjar.tk.server.application.admin

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.server.domain.auth.AuthenticationAttempt
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuard
import com.virjar.tk.server.domain.auth.AuthenticationAttemptKeys
import com.virjar.tk.server.domain.auth.AuthenticationOperation
import com.virjar.tk.server.domain.auth.PasswordHasher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Configuration is only bootstrap/recovery input; a persisted credential is the authority afterwards. */
class AdminBootstrap(
    val username: String? = System.getenv("ADMIN_USER"),
    val password: String? = System.getenv("ADMIN_PASSWORD"),
    val recoveryId: String? = System.getenv("ADMIN_CREDENTIAL_RESET_ID"),
)

@Serializable
data class AdminSessionInfo(
    val id: String,
    val createdAt: Long,
    val expiresAt: Long,
    val source: String,
    val current: Boolean,
)

@Serializable
data class AdminSecurityStatus(val username: String, val passwordUpdatedAt: Long, val sessions: List<AdminSessionInfo>)

enum class AdminPasswordRotationResult { ROTATED, UNAUTHENTICATED, INVALID_CREDENTIALS, RATE_LIMITED }

/** Single owner of admin credentials and bounded, process-local sessions. No application-user roles. */
class AdminSecurityService internal constructor(
    private val store: AdminSecurityStore,
    private val passwordHasher: PasswordHasher,
    private val authenticationAttempts: AuthenticationAttemptGuard,
    private var bootstrap: AdminBootstrap? = AdminBootstrap(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxActiveTokens: Int = DEFAULT_MAX_ACTIVE_TOKENS,
) {
    private val mutex = Mutex()
    private val tokens = LinkedHashMap<String, Session>()
    private val random = SecureRandom()
    private var initialized = false
    private var credential: AdminCredential? = null

    init { require(maxActiveTokens > 0) }

    /** Called before HTTP publication, and lazily for isolated route/test owners. */
    suspend fun initialize() = mutex.withLock { initializeLocked() }

    private suspend fun initializeLocked() {
        if (initialized) return
        val config = checkNotNull(bootstrap)
        val existing = store.credential()
        val resetId = config.recoveryId?.takeIf(String::isNotBlank)
        if (resetId != null) {
            require(runCatching { UUID.fromString(resetId).toString() == resetId }.getOrDefault(false)) {
                "ADMIN_CREDENTIAL_RESET_ID must be a canonical UUID"
            }
        }
        val requiresRecovery = resetId != null && resetId != existing?.recoveryId
        val configured = !config.username.isNullOrBlank() && !config.password.isNullOrBlank()
        require(!requiresRecovery || configured) { "Admin credential recovery requires both ADMIN_USER and ADMIN_PASSWORD" }
        credential = if ((existing == null && configured) || requiresRecovery) {
            val username = checkNotNull(config.username)
            val password = checkNotNull(config.password)
            require(username.length in 1..100 && username.none(Char::isISOControl)) { "Invalid administrator username" }
            val now = clock()
            AdminCredential(username, hashAdminPassword(password), now, resetId).also {
                store.saveCredential(it, if (existing == null) "admin.credentials.initialize" else "admin.credentials.recover", now)
            }
        } else existing
        // Do not retain the environment's plaintext password in the service after initialization.
        bootstrap = null
        initialized = true
    }

    suspend fun login(user: String, pass: String, source: String = "unattributed-admin-peer"): String? {
        val admission = authenticationAttempts.tryAcquire(AuthenticationAttempt(
            AuthenticationOperation.ADMIN,
            AuthenticationAttemptKeys.directSource(source),
            AuthenticationAttemptKeys.username("admin", user),
        )) ?: run {
            recordRejectedLogin(AdminAuditFailureReason.RATE_LIMITED)
            return null
        }
        try {
            return mutex.withLock {
                initializeLocked()
                val current = credential
                val matchingUser = current != null && MessageDigest.isEqual(user.toByteArray(), current.username.toByteArray())
                val verified = verifyAdminPassword(pass, current?.passwordHash?.takeIf { matchingUser })
                if (!matchingUser || !verified) {
                    recordRejectedLogin(AdminAuditFailureReason.INVALID_CREDENTIALS)
                    return@withLock null
                }
                val now = clock()
                removeExpired(now)
                val audit = store.beginAudit(current!!.username, "admin.login", "administrator", now)
                store.completeAudit(audit, 200, now)
                while (tokens.size >= maxActiveTokens) tokens.remove(tokens.keys.first())
                val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
                tokens[tokenKey(token)] = Session(UUID.randomUUID().toString(), current.username, now,
                    if (now > Long.MAX_VALUE - TOKEN_TTL_MS) Long.MAX_VALUE else now + TOKEN_TTL_MS,
                    source.filterNot(Char::isISOControl).take(100))
                token
            }
        } finally { admission.close() }
    }

    suspend fun principal(token: String?): String? = mutex.withLock { session(token)?.principal }

    suspend fun status(token: String): AdminSecurityStatus? = mutex.withLock {
        val current = session(token) ?: return@withLock null
        val configured = checkNotNull(credential)
        AdminSecurityStatus(configured.username, configured.updatedAt, tokens.values.map {
            AdminSessionInfo(it.id, it.createdAt, it.expiresAt, it.source, it.id == current.id)
        })
    }

    suspend fun revoke(token: String, sessionId: String): Boolean = mutex.withLock {
        if (session(token) == null) return@withLock false
        tokens.entries.removeIf { it.value.id == sessionId }
        true // Idempotent: an already revoked/expired session is also absent.
    }

    suspend fun logout(token: String) = mutex.withLock { tokens.remove(tokenKey(token)); Unit }

    suspend fun revokeAll(token: String): Boolean = mutex.withLock {
        if (session(token) == null) return@withLock false
        tokens.clear()
        true
    }

    suspend fun rotatePassword(token: String, currentPassword: String, newPassword: String): AdminPasswordRotationResult = mutex.withLock {
        val currentSession = session(token) ?: return@withLock AdminPasswordRotationResult.UNAUTHENTICATED
        val current = checkNotNull(credential)
        val admission = authenticationAttempts.tryAcquire(AuthenticationAttempt(
            AuthenticationOperation.ADMIN,
            AuthenticationAttemptKeys.directSource(currentSession.source),
            AuthenticationAttemptKeys.username("admin", current.username),
        )) ?: return@withLock AdminPasswordRotationResult.RATE_LIMITED
        try {
            if (!verifyAdminPassword(currentPassword, current.passwordHash)) return@withLock AdminPasswordRotationResult.INVALID_CREDENTIALS
            require(AuthRules.validatePassword(newPassword) == null) { "Invalid administrator password" }
            require(newPassword != currentPassword) { "Administrator password must change" }
            val replacement = AdminCredential(current.username, passwordHasher.hash(newPassword), clock(), current.recoveryId)
            store.saveCredential(replacement, "admin.credentials.rotate", replacement.updatedAt)
            credential = replacement
            tokens.clear()
            AdminPasswordRotationResult.ROTATED
        } finally { admission.close() }
    }

    fun beginAudit(actor: String, action: String, target: String): Long = store.beginAudit(actor, action, target, clock())
    fun completeAudit(id: Long, status: Int, failureReason: AdminAuditFailureReason? = AdminAuditFailureReason.forStatus(status)) =
        store.completeAudit(id, status, clock(), failureReason)
    fun audits(beforeId: Long?, limit: Int) = store.audits(beforeId, limit)
    internal suspend fun activeTokenCount(): Int = mutex.withLock { removeExpired(clock()); tokens.size }

    private fun recordRejectedLogin(reason: AdminAuditFailureReason) {
        val now = clock()
        // The single administrator is known; unverified usernames are request data, not audit principals.
        val id = store.beginAudit("unauthenticated", "admin.login", "administrator", now)
        store.completeAudit(id, 401, now, reason)
    }

    // Bootstrap previously accepted every non-blank environment password. Preserve short credentials,
    // and prehash legacy long passwords explicitly instead of BCrypt's lossy 72-byte truncation.
    private suspend fun hashAdminPassword(password: String): String =
        if (password.toByteArray(Charsets.UTF_8).size > AuthRules.PASSWORD_MAX_UTF8_BYTES) {
            "sha256:" + passwordHasher.hash(adminPasswordDigest(password))
        } else passwordHasher.hash(password)

    private suspend fun verifyAdminPassword(password: String, encoded: String?): Boolean =
        if (encoded?.startsWith("sha256:") == true) {
            passwordHasher.verify(adminPasswordDigest(password), encoded.removePrefix("sha256:"))
        } else passwordHasher.verify(password, encoded)

    private fun adminPasswordDigest(password: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8)),
    )

    private fun session(token: String?): Session? {
        removeExpired(clock())
        return if (token.isNullOrBlank()) null else tokens[tokenKey(token)]
    }
    private fun removeExpired(now: Long) { tokens.entries.removeIf { now >= it.value.expiresAt } }
    private fun tokenKey(token: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    private class Session(val id: String, val principal: String, val createdAt: Long, val expiresAt: Long, val source: String)

    companion object {
        const val TOKEN_TTL_MS = 12 * 3600 * 1000L
        const val DEFAULT_MAX_ACTIVE_TOKENS = 256
    }
}
