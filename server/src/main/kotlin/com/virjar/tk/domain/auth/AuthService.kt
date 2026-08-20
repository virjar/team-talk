package com.virjar.tk.domain.auth

import com.virjar.tk.domain.user.UserService
import com.virjar.tk.domain.session.OnlineSessions
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthenticationResult(
    val response: AuthResponsePayload,
    val principal: TokenInfo?,
) {
    override fun toString(): String =
        "AuthenticationResult(code=${response.code}, hasPrincipal=${principal != null}, credentials=<redacted>)"
}

class AuthService(
    private val userService: UserService,
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
    }

    suspend fun handleAuth(payload: AuthRequestPayload): AuthResponsePayload = authenticate(payload).response

    suspend fun authenticate(payload: AuthRequestPayload): AuthenticationResult {
        // 版本检查由 AUTH 序言魔承担；不匹配会在进入 AuthService 前回写专用拒绝码。
        // payload 内 protocolVersion 字段已删（曾与之重复且恒真）
        if (!isValidDeviceId(payload.deviceId)) {
            return failure("Invalid device id")
        }
        return when (payload.authType) {
            0 -> handleLogin(payload)      // login
            1 -> handleRegister(payload)   // register
            2 -> handleRefresh(payload)    // refresh token
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
        val name = payload.name?.takeIf { it.isNotBlank() } ?: username

        val proof = try {
            userService.registerForCredentialIssue(username, password, name)
        } catch (e: IllegalArgumentException) {
            return failure(e.message)
        }

        return issueTokens(proof, payload)
    }

    private suspend fun handleRefresh(payload: AuthRequestPayload): AuthenticationResult {
        val refreshToken = payload.refreshToken?.takeIf { it.isNotBlank() }
            ?: return failure("Missing refresh token")

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
        val issued = commitCredentialMutationAndFence(
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
        ) ?: return failure("账号状态已变化，请重新登录")
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

    private fun AuthRequestPayload.toCredentialDevice() = CredentialDevice(
        deviceId = deviceId,
        deviceName = deviceName,
        deviceModel = deviceModel,
        deviceFlag = deviceFlag,
    )

    private suspend fun publishDeviceCredentialFence(principal: TokenInfo) {
        // The pair is already committed. Publish its generation before AUTH succeeds so an older
        // delayed login can neither remain live nor supersede this credential generation.
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
        // Keep the synchronous Exposed read off the CPU pool; only BCrypt belongs on Default.
        val internal = withContext(Dispatchers.IO) { userService.passwordChangeSource(uid, newPassword) }
        val proof = withContext(Dispatchers.Default) {
            userService.preparePasswordChange(internal, oldPassword, newPassword)
        }
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

internal fun isValidDeviceId(value: String): Boolean =
    value.length in 1..100 &&
        value != "." &&
        value != ".." &&
        value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
