package com.virjar.tk.domain.device

import com.virjar.tk.model.Device

/** Persistence port owned by the device domain. */
interface DeviceRepository {
    fun registerDevice(uid: String, deviceId: String, deviceName: String?, deviceModel: String?, deviceFlag: Int)
    fun getDevices(uid: String): List<DeviceRecord>
    fun kickDevice(uid: String, deviceId: String)
}

data class DeviceRecord(
    val id: Long,
    val uid: String,
    val deviceId: String,
    val deviceName: String?,
    val deviceModel: String?,
    val deviceFlag: Int,
    val lastLogin: Long,
)

fun DeviceRecord.toModel() = Device(
    deviceId = deviceId,
    deviceName = deviceName,
    deviceModel = deviceModel,
    deviceFlag = deviceFlag,
    lastLogin = lastLogin,
)
