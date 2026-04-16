package com.virjar.tk.service

import com.virjar.tk.dto.DeviceDto
import com.virjar.tk.store.DeviceStore
import com.virjar.tk.tcp.ClientRegistry
import org.slf4j.LoggerFactory

object DeviceService {
    private val logger = LoggerFactory.getLogger(DeviceService::class.java)
    private lateinit var deviceStore: DeviceStore

    fun init(deviceStore: DeviceStore) {
        this.deviceStore = deviceStore
    }

    suspend fun registerDevice(uid: String, deviceId: String, deviceName: String, deviceModel: String, deviceFlag: Int) {
        deviceStore.upsertDevice(uid, deviceId, deviceName, deviceModel, deviceFlag)
    }

    suspend fun getDevices(uid: String): List<DeviceDto> {
        val onlineDeviceIds = ClientRegistry.getOnlineDeviceIds(uid)
        val rows = deviceStore.findByUid(uid)
        return rows.map { row ->
            DeviceDto(
                deviceId = row.deviceId,
                deviceName = row.deviceName,
                deviceModel = row.deviceModel,
                deviceFlag = row.deviceFlag,
                lastLogin = row.lastLogin ?: 0,
                isOnline = row.deviceId in onlineDeviceIds,
            )
        }
    }

    suspend fun kickDevice(uid: String, targetDeviceId: String) {
        ClientRegistry.kickDevice(uid, targetDeviceId)
        deviceStore.deleteDevice(uid, targetDeviceId)
    }
}
