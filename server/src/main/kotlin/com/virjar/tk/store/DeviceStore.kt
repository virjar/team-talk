package com.virjar.tk.store

import com.virjar.tk.db.DeviceDao
import com.virjar.tk.db.DeviceRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备 Store：按需 DB 访问，不缓存。低频操作。
 */
class DeviceStore {

    suspend fun upsertDevice(
        uid: String,
        deviceId: String,
        deviceName: String,
        deviceModel: String,
        deviceFlag: Int,
    ): DeviceRow = withContext(Dispatchers.IO) {
        DeviceDao.upsertDevice(uid, deviceId, deviceName, deviceModel, deviceFlag)
    }

    suspend fun findByUid(uid: String): List<DeviceRow> = withContext(Dispatchers.IO) {
        DeviceDao.findByUid(uid)
    }

    suspend fun deleteDevice(uid: String, deviceId: String): Boolean = withContext(Dispatchers.IO) {
        DeviceDao.deleteDevice(uid, deviceId)
    }
}
