package com.virjar.tk.domain.auth

import com.virjar.tk.domain.user.UserService
import com.virjar.tk.model.User
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload

class AuthService(
    private val userService: UserService,
    private val tokenStore: TokenStore,
) {
    companion object {
        const val CODE_OK = 0
        const val CODE_AUTH_FAILED = 1
        const val CODE_VERSION_UNSUPPORTED = 2
        const val CODE_SERVER_MAINTENANCE = 3
        const val CODE_DEVICE_BANNED = 4
        const val CODE_TOO_MANY_CONNECTIONS = 5
    }

    fun handleAuth(payload: AuthRequestPayload): AuthResponsePayload {
        // 版本检查
        if (payload.protocolVersion != com.virjar.tk.protocol.Frame.PROTOCOL_VERSION.toInt()) {
            return AuthResponsePayload(code = CODE_VERSION_UNSUPPORTED, reason = "Unsupported protocol version")
        }

        return when (payload.authType) {
            0 -> handleLogin(payload)      // login
            1 -> handleRegister(payload)   // register
            2 -> handleRefresh(payload)    // refresh token
            else -> AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Unknown auth type")
        }
    }

    private fun handleLogin(payload: AuthRequestPayload): AuthResponsePayload {
        val username = payload.username?.takeIf { it.isNotBlank() }
            ?: return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Missing username")
        val password = payload.password?.takeIf { it.isNotBlank() }
            ?: return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Missing password")

        val user = try {
            userService.login(username, password)
        } catch (e: IllegalArgumentException) {
            return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = e.message)
        }

        return issueTokens(user, payload.deviceId, payload.deviceFlag ?: 0)
    }

    private fun handleRegister(payload: AuthRequestPayload): AuthResponsePayload {
        val username = payload.username?.takeIf { it.isNotBlank() }
            ?: return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Missing username")
        val password = payload.password?.takeIf { it.isNotBlank() }
            ?: return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Missing password")
        val name = payload.name?.takeIf { it.isNotBlank() } ?: username

        val user = try {
            userService.register(username, password, name)
        } catch (e: IllegalArgumentException) {
            return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = e.message)
        }

        return issueTokens(user, payload.deviceId, payload.deviceFlag ?: 0)
    }

    private fun handleRefresh(payload: AuthRequestPayload): AuthResponsePayload {
        val refreshToken = payload.refreshToken?.takeIf { it.isNotBlank() }
            ?: return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Missing refresh token")

        val newTokens = tokenStore.refreshAccessToken(refreshToken)
            ?: return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Invalid or expired refresh token")

        val info = tokenStore.validateAccessToken(newTokens.first)
            ?: return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Token validation failed")

        return AuthResponsePayload(
            code = CODE_OK,
            uid = info.uid,
            accessToken = newTokens.first,
            refreshToken = newTokens.second,
            expiresIn = 30 * 24 * 3600L,
        )
    }

    private fun issueTokens(user: User, deviceId: String, deviceFlag: Int): AuthResponsePayload {
        val (accessToken, refreshToken) = tokenStore.generateTokens(user.uid, deviceId, deviceFlag)
        return AuthResponsePayload(
            code = CODE_OK,
            uid = user.uid,
            username = user.username,
            name = user.name,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = 30 * 24 * 3600L,
        )
    }

    fun validateToken(token: String): TokenStore.TokenInfo? {
        return tokenStore.validateAccessToken(token)
    }

    fun kickDevice(uid: String, deviceId: String) {
        tokenStore.revokeAllDeviceTokens(uid, deviceId)
    }

    fun logout(uid: String, refreshToken: String?, deviceId: String? = null) {
        // 废弃 refreshToken
        if (refreshToken != null) {
            tokenStore.refreshAccessToken(refreshToken)
        }
        // 仅清除当前设备的 token，不影响其他设备
        if (deviceId != null) {
            tokenStore.revokeAllDeviceTokens(uid, deviceId)
        }
    }

    fun changePassword(uid: String, oldPassword: String, newPassword: String) {
        userService.changePassword(uid, oldPassword, newPassword)
    }
}
