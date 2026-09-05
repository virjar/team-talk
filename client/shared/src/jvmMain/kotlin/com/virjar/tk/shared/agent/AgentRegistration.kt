package com.virjar.tk.shared.agent

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.shared.bot.ImBotAuthenticationRejectedException
import com.virjar.tk.shared.client.AuthenticationFailureKind
import com.virjar.tk.shared.client.DeploymentIdentity
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal object AgentRegistration {
    private val secureRandom = SecureRandom()

    fun beginOrResume(
        dataDir: File,
        deploymentIdentity: DeploymentIdentity,
        prefix: String,
    ): AgentCredentialRecord {
        AgentCredentials.load(dataDir, deploymentIdentity)?.let { existing ->
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
        return AgentCredentials.beginRegistration(dataDir, deploymentIdentity, username, password)
    }

    /**
     * 待处理的注册先用精确的持久身份登录。只有登录失败
     * 才尝试精确注册。每条 ImBot AUTH 回调必须在任一连接
     * 路径返回之前原子地激活仅 refresh 的凭据。
     */
    suspend fun <T : Any> recover(
        dataDir: File,
        deploymentIdentity: DeploymentIdentity,
        pending: AgentCredentialRecord,
        login: suspend (AgentCredentialRecord) -> T,
        registerExact: suspend (AgentCredentialRecord) -> T,
        discard: (T) -> Unit = {},
    ): T {
        require(pending.state == AgentCredentialState.REGISTER_PENDING) { "Registration is not pending" }
        require(pending.username != null && pending.password != null) { "Pending registration is incomplete" }
        val durable = requireNotNull(AgentCredentials.load(dataDir, deploymentIdentity)) {
            "Pending registration credentials are missing"
        }
        require(
            durable.state == AgentCredentialState.REGISTER_PENDING &&
                durable.username == pending.username &&
                durable.password == pending.password &&
                durable.deviceId == pending.deviceId &&
                durable.apiToken == pending.apiToken &&
                durable.deploymentFingerprint == pending.deploymentFingerprint
        ) {
            "Pending registration does not match durable credentials"
        }
        var connected: T? = null
        try {
            connected = try {
                login(durable)
            } catch (loginFailure: ImBotAuthenticationRejectedException) {
                // 维护、限流、版本与设备策略失败永远不能证明
                // 那个精确的持久账号不存在，因此它们绝不能触发注册。
                if (loginFailure.kind != AuthenticationFailureKind.REJECTED) throw loginFailure
                val afterLogin = AgentCredentials.load(dataDir, deploymentIdentity)
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
            val active = requireNotNull(AgentCredentials.load(dataDir, deploymentIdentity)) {
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
            deviceId == expected.deviceId && apiToken == expected.apiToken &&
            deploymentFingerprint == expected.deploymentFingerprint
}
