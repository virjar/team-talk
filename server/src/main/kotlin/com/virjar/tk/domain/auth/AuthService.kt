package com.virjar.tk.domain.auth

import com.virjar.tk.domain.user.UserService
import com.virjar.tk.domain.device.DeviceRepository
import com.virjar.tk.model.User
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload

class AuthService(
    private val userService: UserService,
    private val tokenStore: TokenRepository,
    private val devices: DeviceRepository,
) {
    companion object {
        const val CODE_OK = AuthResponsePayload.CODE_OK
        const val CODE_AUTH_FAILED = AuthResponsePayload.CODE_AUTH_FAILED
        const val CODE_VERSION_UNSUPPORTED = AuthResponsePayload.CODE_VERSION_UNSUPPORTED
        const val CODE_SERVER_MAINTENANCE = AuthResponsePayload.CODE_SERVER_MAINTENANCE
        const val CODE_DEVICE_BANNED = AuthResponsePayload.CODE_DEVICE_BANNED
        const val CODE_TOO_MANY_CONNECTIONS = AuthResponsePayload.CODE_TOO_MANY_CONNECTIONS
    }

    fun handleAuth(payload: AuthRequestPayload): AuthResponsePayload {
        // 版本检查由 AUTH 序言魔承担；不匹配会在进入 AuthService 前回写专用拒绝码。
        // payload 内 protocolVersion 字段已删（曾与之重复且恒真）
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

        return issueTokens(user, payload)
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

        return issueTokens(user, payload)
    }

    private fun handleRefresh(payload: AuthRequestPayload): AuthResponsePayload {
        val refreshToken = payload.refreshToken?.takeIf { it.isNotBlank() }
            ?: return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Missing refresh token")

        val newTokens = tokenStore.refreshAccessToken(
            refreshToken,
            expectedDeviceId = payload.deviceId,
            expectedDeviceFlag = payload.deviceFlag,
        )
            ?: return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Invalid or expired refresh token")

        val info = tokenStore.validateAccessToken(newTokens.first)
            ?: return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "Token validation failed")

        // 封禁复查：refresh 路径只验 token 不查用户状态——ban 后已发 token 仍可续期（曾可绕过）
        // 顺带取回 user：refresh 响应必须带 username/name（与 login/register 的 issueTokens 对齐），
        // 否则客户端自动登录后 UserSession 身份为空，头像/昵称退化为 uid（曾现 '?' 头像）
        val user = try {
            userService.getProfile(info.uid)
        } catch (e: IllegalArgumentException) {
            return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = e.message)
        }
        if (user.status == 2) {
            return AuthResponsePayload(code = CODE_AUTH_FAILED, reason = "账号已被封禁")
        }

        registerDevice(user.uid, payload)

        return AuthResponsePayload(
            code = CODE_OK,
            uid = info.uid,
            username = user.username,
            name = user.name,
            accessToken = newTokens.first,
            refreshToken = newTokens.second,
            expiresIn = 30 * 24 * 3600L,
        )
    }

    private fun issueTokens(user: User, payload: AuthRequestPayload): AuthResponsePayload {
        val (accessToken, refreshToken) = tokenStore.generateTokens(user.uid, payload.deviceId, payload.deviceFlag)
        registerDevice(user.uid, payload)
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

    private fun registerDevice(uid: String, payload: AuthRequestPayload) {
        devices.registerDevice(
            uid = uid,
            deviceId = payload.deviceId,
            deviceName = payload.deviceName,
            deviceModel = payload.deviceModel,
            deviceFlag = payload.deviceFlag,
        )
    }

    fun validateToken(token: String): TokenInfo? {
        return tokenStore.validateAccessToken(token)
    }

    fun kickDevice(uid: String, deviceId: String) {
        tokenStore.revokeAllDeviceTokens(uid, deviceId)
    }

    fun logout(uid: String, refreshToken: String?, deviceId: String? = null): Boolean {
        // deviceId/refreshToken 都来自 RPC payload，必须先与 token 内的 uid/device 绑定；
        // 否则一个已认证客户端可以把“退出”伪装成另一个设备，污染设备表或误吊销凭证。
        if (refreshToken == null || deviceId == null) return false
        val owned = tokenStore.revokeRefreshToken(
            refreshToken,
            expectedUid = uid,
            expectedDeviceId = deviceId,
        )
        if (!owned) return false
        tokenStore.revokeAllDeviceTokens(uid, deviceId)
        devices.kickDevice(uid, deviceId)
        return true
    }

    fun changePassword(uid: String, oldPassword: String, newPassword: String) {
        userService.changePassword(uid, oldPassword, newPassword)
    }
}
