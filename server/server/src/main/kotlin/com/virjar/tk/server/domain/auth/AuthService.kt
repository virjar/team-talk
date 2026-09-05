package com.virjar.tk.server.domain.auth

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.server.domain.user.UserService
import com.virjar.tk.server.domain.session.OnlineSessions
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AuthenticationResult(
    val response: AuthResponsePayload,
    val principal: TokenInfo?,
) {
    override fun toString(): String =
        "AuthenticationResult(code=${response.code}, hasPrincipal=${principal != null}, credentials=<redacted>)"
}

class AuthService(
    private val userService: UserService,
    private val registrationService: RegistrationService,
    private val credentials: TokenRepository,
    private val onlineSessions: OnlineSessions,
) {
    companion object {
        const val CODE_OK = AuthResponsePayload.CODE_OK
        const val CODE_AUTH_FAILED = AuthResponsePayload.CODE_AUTH_FAILED
        const val CODE_VERSION_UNSUPPORTED = AuthResponsePayload.CODE_VERSION_UNSUPPORTED
        const val CODE_SERVER_MAINTENANCE = AuthResponsePayload.CODE_SERVER_MAINTENANCE
        const val CODE_DEVICE_BANNED = AuthResponsePayload.CODE_DEVICE_BANNED
        const val CODE_TOO_MANY_CONNECTIONS = AuthResponsePayload.CODE_TOO_MANY_CONNECTIONS
        const val DEVICE_LIMIT_RESPONSE_REASON = "设备数量已达上限"
    }

    suspend fun handleAuth(payload: AuthRequestPayload): AuthResponsePayload = authenticate(payload).response

    suspend fun authenticate(payload: AuthRequestPayload): AuthenticationResult {
        // 连接适配器在 AUTH 前完成协议协商。这里只裁决凭据与设备，
        // 不把传输版本策略混入领域认证。
        try {
            AuthRules.validateDevice(
                payload.deviceId,
                payload.deviceName,
                payload.deviceModel,
                payload.deviceFlag,
            )
        } catch (failure: IllegalArgumentException) {
            return failure(failure.message)
        }
        return when (payload.authType) {
            0 -> handleLogin(payload)      // 登录
            1 -> handleRegister(payload)   // 注册
            2 -> handleRefresh(payload)    // 刷新令牌
            else -> failure("Unknown auth type")
        }
    }

    private suspend fun handleLogin(payload: AuthRequestPayload): AuthenticationResult {
        val username = payload.username?.takeIf { it.isNotBlank() }
            ?: return failure("Missing username")
        val password = payload.password?.takeIf { it.isNotBlank() }
            ?: return failure("Missing password")

        val proof = try {
            userService.authenticateForCredentialIssue(username, password)
        } catch (e: IllegalArgumentException) {
            return failure(e.message)
        }

        return issueTokens(proof, payload)
    }

    private suspend fun handleRegister(payload: AuthRequestPayload): AuthenticationResult {
        val username = payload.username?.takeIf { it.isNotBlank() }
            ?: return failure("Missing username")
        val password = payload.password?.takeIf { it.isNotBlank() }
            ?: return failure("Missing password")
        val name = payload.name ?: return failure("显示名不能为空")
        AuthRules.validateDisplayName(name)?.let { return failure(it) }

        val registration = try {
            registrationService.register(
                username = username,
                password = password,
                name = name,
                device = payload.toCredentialDevice(),
            )
        } catch (e: IllegalArgumentException) {
            return failure(e.message)
        }

        val issued = registration.credentials
        return AuthenticationResult(
            response = successResponse(
                registration.user.uid,
                registration.user.username,
                registration.user.name,
                issued,
            ),
            principal = issued.principal,
        )
    }

    private suspend fun handleRefresh(payload: AuthRequestPayload): AuthenticationResult {
        val refreshToken = payload.refreshToken?.takeIf { it.isNotBlank() }
            ?: return failure("Missing refresh token")

        currentCoroutineContext().ensureActive()
        val issued = commitCredentialMutationAndFence(
            commit = { credentials.refreshCredentials(refreshToken, payload.toCredentialDevice()) },
            publishFence = { committed -> committed?.let { publishDeviceCredentialFence(it.principal) } },
        ) ?: return failure("Invalid or expired refresh token")

        return AuthenticationResult(
            response = successResponse(
                issued.principal.uid,
                issued.subject.username,
                issued.subject.name,
                issued,
            ),
            principal = issued.principal,
        )
    }

    private suspend fun issueTokens(
        proof: UserService.CredentialLoginProof,
        payload: AuthRequestPayload,
    ): AuthenticationResult {
        currentCoroutineContext().ensureActive()
        val issued = try {
            commitCredentialMutationAndFence(
                commit = {
                    credentials.issueCredentials(
                        CredentialIssueRequest(
                            uid = proof.user.uid,
                            expectedUserCredentialEpoch = proof.userCredentialEpoch,
                            expectedPasswordHash = proof.passwordHashSnapshot,
                            device = payload.toCredentialDevice(),
                        ),
                    )
                },
                publishFence = { committed -> committed?.let { publishDeviceCredentialFence(it.principal) } },
            )
        } catch (_: AuthenticatedDeviceLimitReachedException) {
            // 容量只有在完整的密码证明之后才可观察。这个预期的公开结果中不要包含 uid、
            // 设备 id、数量或持久化细节。
            return deviceLimitFailure()
        } ?: return failure("账号状态已变化，请重新登录")
        return AuthenticationResult(
            response = successResponse(
                issued.principal.uid,
                issued.subject.username,
                issued.subject.name,
                issued,
            ),
            principal = issued.principal,
        )
    }

    private fun successResponse(
        uid: String,
        username: String?,
        name: String,
        issued: IssuedCredentials,
    ) = AuthResponsePayload(
        code = CODE_OK,
        uid = uid,
        username = username,
        name = name,
        accessToken = issued.accessToken,
        refreshToken = issued.refreshToken,
        expiresIn = 30 * 24 * 3600L,
    )

    private fun failure(reason: String?) = AuthenticationResult(
        response = AuthResponsePayload(code = CODE_AUTH_FAILED, reason = reason),
        principal = null,
    )

    private fun deviceLimitFailure() = AuthenticationResult(
        response = AuthResponsePayload(
            code = CODE_TOO_MANY_CONNECTIONS,
            reason = DEVICE_LIMIT_RESPONSE_REASON,
        ),
        principal = null,
    )

    private fun AuthRequestPayload.toCredentialDevice() = CredentialDevice(
        deviceId = deviceId,
        deviceName = deviceName,
        deviceModel = deviceModel,
        deviceFlag = deviceFlag,
    )

    private suspend fun publishDeviceCredentialFence(principal: TokenInfo) {
        // 凭证对已经提交。在 AUTH 成功之前先发布其代号（generation），这样更早的延迟登录
        // 既不能继续保持活跃，也不能取代这一代凭证。
        onlineSessions.invalidateDeviceCredentials(
            principal.uid,
            principal.deviceId,
            principal.deviceCredentialEpoch,
        )
    }

    suspend fun revokeDevice(uid: String, deviceId: String): Long? {
        return commitCredentialMutationAndFence(
            commit = { credentials.revokeDevice(uid, deviceId) },
            publishFence = { epoch -> epoch?.let { onlineSessions.invalidateDeviceCredentials(uid, deviceId, it) } },
        )
    }

    suspend fun logoutCurrentSession(
        uid: String,
        deviceId: String,
        expectedDeviceCredentialEpoch: Long,
        responseSessionId: String,
    ) {
        val epoch = commitCredentialMutationAndFence(
            commit = {
                credentials.revokeDeviceIfCurrent(
                    uid = uid,
                    deviceId = deviceId,
                    expectedDeviceCredentialEpoch = expectedDeviceCredentialEpoch,
                )
            },
            publishFence = { committedEpoch ->
                committedEpoch?.let {
                    onlineSessions.invalidateDeviceCredentialsExceptSession(
                        uid = uid,
                        deviceId = deviceId,
                        minimumEpoch = it,
                        sessionId = responseSessionId,
                    )
                }
            },
        )
        requireNotNull(epoch) { "当前设备已经退出" }
    }

    suspend fun changePassword(
        uid: String,
        oldPassword: String,
        newPassword: String,
        responseSessionId: String? = null,
    ) {
        val proof = userService.preparePasswordChange(uid, oldPassword, newPassword)
        val epoch = commitCredentialMutationAndFence(
            commit = {
                credentials.changePasswordAndRevoke(
                uid = uid,
                expectedPasswordHash = proof.expectedPasswordHash,
                newPasswordHash = proof.newPasswordHash,
                )
            },
            publishFence = { committedEpoch ->
                committedEpoch?.let {
                    if (responseSessionId == null) {
                        onlineSessions.invalidateUserCredentials(uid, it)
                    } else {
                        onlineSessions.invalidateUserCredentialsExceptSession(uid, it, responseSessionId)
                    }
                }
            },
        )
        if (epoch == null) throw IllegalArgumentException("密码或账号状态已变化，请重试")
    }
}
