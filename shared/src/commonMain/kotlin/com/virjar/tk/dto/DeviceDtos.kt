package com.virjar.tk.dto

import kotlinx.serialization.Serializable

@Serializable
data class DeviceDto(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val deviceFlag: Int,
    val lastLogin: Long,
    val isOnline: Boolean,
)
