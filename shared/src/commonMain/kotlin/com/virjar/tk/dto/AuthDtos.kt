package com.virjar.tk.dto

import kotlinx.serialization.Serializable

@Serializable
data class AuthResponse(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: UserDto,
)

@Serializable
data class UserDto(
    val uid: String,
    val username: String? = null,
    val name: String,
    val phone: String? = null,
    val zone: String = "86",
    val avatar: String = "",
    val sex: Int = 0,
    val shortNo: String? = null,
    val role: Int = 0,
)

@Serializable
data class RegisterRequest(
    val zone: String = "86",
    val phone: String? = null,
    val username: String? = null,
    val password: String,
    val name: String,
    val device: DeviceInfo? = null,
)

@Serializable
data class LoginRequest(
    val username: String? = null,
    val phone: String? = null,
    val zone: String = "86",
    val password: String,
    val device: DeviceInfo? = null,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class DeviceInfo(
    val deviceId: String = "",
    val deviceName: String = "",
    val deviceModel: String = "",
    val deviceFlag: Int = 0,
)

@Serializable
data class UpdateProfileRequest(
    val name: String? = null,
    val avatar: String? = null,
    val sex: Int? = null,
)
