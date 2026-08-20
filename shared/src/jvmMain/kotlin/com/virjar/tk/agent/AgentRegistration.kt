package com.virjar.tk.agent

import com.virjar.tk.auth.AuthRules
import com.virjar.tk.bot.ImBotAuthenticationRejectedException
import com.virjar.tk.client.AuthenticationFailureKind
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal object AgentRegistration {
    private val secureRandom = SecureRandom()

    fun beginOrResume(dataDir: File, prefix: String): AgentCredentialRecord {
        AgentCredentials.load(dataDir)?.let { existing ->
            when (existing.state) {
                AgentCredentialState.ACTIVE -> error("ACTIVE agent dataDir cannot start registration")
                AgentCredentialState.REGISTER_PENDING -> return existing
                null -> Unit
            }
        }
        val suffix = UUID.randomUUID().toString().take(8)
        val normalizedPrefix = prefix.trim().ifBlank { "agent" }
            .take(AuthRules.USERNAME_MAX_LENGTH - suffix.length - 1)
        require(normalizedPrefix.none(Char::isISOControl)) { "Registration prefix contains control characters" }
        val username = "$normalizedPrefix-$suffix"
        val password = ByteArray(24).also { secureRandom.nextBytes(it) }.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        return AgentCredentials.beginRegistration(dataDir, username, password)
    }

    /**
     * A pending registration first logs in with the exact durable identity. Only a failed login
     * attempts exact registration. Each ImBot AUTH callback must atomically activate refresh-only
     * credentials before either connection path can return.
     */
    suspend fun <T : Any> recover(
        dataDir: File,
        pending: AgentCredentialRecord,
        login: suspend (AgentCredentialRecord) -> T,
        registerExact: suspend (AgentCredentialRecord) -> T,
        discard: (T) -> Unit = {},
    ): T {
        require(pending.state == AgentCredentialState.REGISTER_PENDING) { "Registration is not pending" }
        require(pending.username != null && pending.password != null) { "Pending registration is incomplete" }
        val durable = requireNotNull(AgentCredentials.load(dataDir)) {
            "Pending registration credentials are missing"
        }
        require(
            durable.state == AgentCredentialState.REGISTER_PENDING &&
                durable.username == pending.username &&
                durable.password == pending.password &&
                durable.deviceId == pending.deviceId &&
                durable.apiToken == pending.apiToken
        ) {
            "Pending registration does not match durable credentials"
        }
        var connected: T? = null
        try {
            connected = try {
                login(durable)
            } catch (loginFailure: ImBotAuthenticationRejectedException) {
                // Maintenance, throttling, version and device policy failures never prove that
                // the exact durable account is absent, so they must not trigger registration.
                if (loginFailure.kind != AuthenticationFailureKind.REJECTED) throw loginFailure
                val afterLogin = AgentCredentials.load(dataDir)
                if (afterLogin?.state == AgentCredentialState.ACTIVE) throw loginFailure
                require(afterLogin.matchesPending(durable)) {
                    "Pending registration changed after failed login"
                }
                try {
                    registerExact(durable)
                } catch (registrationFailure: Throwable) {
                    registrationFailure.addSuppressed(loginFailure)
                    throw registrationFailure
                }
            }
            val active = requireNotNull(AgentCredentials.load(dataDir)) {
                "Authentication returned without durable ACTIVE credentials"
            }
            require(
                active.state == AgentCredentialState.ACTIVE &&
                    active.username == durable.username &&
                    active.deviceId == durable.deviceId &&
                    active.password == null && active.uid != null && active.refreshToken != null
            ) {
                "Authentication returned without activating the exact durable identity"
            }
            return requireNotNull(connected)
        } catch (failure: Throwable) {
            connected?.let(discard)
            throw failure
        }
    }

    private fun AgentCredentialRecord?.matchesPending(expected: AgentCredentialRecord): Boolean =
        this != null &&
            state == AgentCredentialState.REGISTER_PENDING &&
            username == expected.username && password == expected.password &&
            deviceId == expected.deviceId && apiToken == expected.apiToken
}
