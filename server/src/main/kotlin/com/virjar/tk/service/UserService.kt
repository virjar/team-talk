package com.virjar.tk.service

import com.virjar.tk.api.BusinessException
import com.virjar.tk.db.TokenDao
import com.virjar.tk.db.UserRow
import com.virjar.tk.dto.*
import com.virjar.tk.protocol.AuthErrorCode
import com.virjar.tk.store.UserStore
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

class UserService(
    private val tokenService: TokenService,
    private val userStore: UserStore,
) {
    private val logger = LoggerFactory.getLogger(UserService::class.java)

    suspend fun register(req: RegisterRequest): AuthResponse {
        val username = req.username
        val phone = req.phone
        if (username.isNullOrBlank() && phone.isNullOrBlank()) {
            error("username or phone is required")
        }
        if (username != null && userStore.findByUsername(username) != null) {
            throw BusinessException(AuthErrorCode.USER_ALREADY_EXISTS, "username already exists", HttpStatusCode.Conflict)
        }
        if (phone != null && userStore.findByPhone(req.zone, phone) != null) {
            throw BusinessException(AuthErrorCode.USER_ALREADY_EXISTS, "phone already registered", HttpStatusCode.Conflict)
        }

        val uid = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val user = userStore.create(
            uid = uid,
            username = username,
            name = req.name,
            phone = phone,
            zone = req.zone,
            plainPassword = req.password,
        )

        return issueTokens(user, req.device?.deviceFlag ?: 0, req.device)
    }

    suspend fun login(req: LoginRequest): AuthResponse {
        val username = req.username
        val phone = req.phone
        val user = when {
            !username.isNullOrBlank() -> userStore.findByUsername(username)
            !phone.isNullOrBlank() -> userStore.findByPhone(req.zone, phone)
            else -> error("username or phone is required")
        } ?: error("invalid credentials")

        if (user.status != 1) {
            error("account is disabled")
        }

        if (!userStore.verifyPassword(user.uid, req.password)) {
            error("invalid credentials")
        }

        logger.info("User login: uid={}", user.uid)
        return issueTokens(user, req.device?.deviceFlag ?: 0, req.device)
    }

    suspend fun refresh(refreshToken: String): AuthResponse {
        val (uid, _) = withContext(Dispatchers.IO) {
            TokenDao.findByRefreshToken(refreshToken)
        } ?: error("invalid or expired refresh token")
        val user = userStore.findByUid(uid) ?: error("user not found")

        withContext(Dispatchers.IO) { TokenDao.deleteByRefreshToken(refreshToken) }
        return issueTokens(user, 0, null)
    }

    suspend fun logout(uid: String, refreshToken: String?) {
        if (refreshToken != null) {
            withContext(Dispatchers.IO) { TokenDao.deleteByRefreshToken(refreshToken) }
        }
        logger.info("User logged out: uid={}", uid)
    }

    suspend fun getUser(uid: String): UserDto {
        val user = userStore.findByUid(uid) ?: error("user not found")
        return user.toResponse()
    }

    suspend fun updateUser(uid: String, name: String?, avatar: String?, sex: Int?): UserDto {
        userStore.updateProfile(uid, name, avatar, sex)
        val user = userStore.findByUid(uid) ?: error("user not found")
        return user.toResponse()
    }

    suspend fun searchUsers(query: String): List<UserDto> {
        return userStore.search(query).map { it.toResponse() }
    }

    private suspend fun issueTokens(user: UserRow, deviceFlag: Int, device: DeviceInfo?): AuthResponse {
        val tokenPair = tokenService.generateTokenPair(user.uid)
        withContext(Dispatchers.IO) {
            TokenDao.create(
                uid = user.uid,
                refreshToken = tokenPair.refreshToken,
                deviceFlag = deviceFlag,
                expiresAt = tokenService.getRefreshTokenExpiry(),
            )
        }

        // 注册设备（登录/注册时携带设备信息）
        if (device != null && device.deviceId.isNotEmpty()) {
            DeviceService.registerDevice(
                user.uid, device.deviceId, device.deviceName, device.deviceModel, device.deviceFlag
            )
        }

        return AuthResponse(
            uid = user.uid,
            accessToken = tokenPair.accessToken,
            refreshToken = tokenPair.refreshToken,
            expiresIn = tokenPair.expiresIn,
            user = user.toResponse(),
        )
    }

    private fun UserRow.toResponse() = UserDto(
        uid = uid,
        username = username,
        name = name,
        phone = phone,
        zone = zone,
        avatar = avatar,
        sex = sex,
        shortNo = shortNo,
        role = role,
    )
}
