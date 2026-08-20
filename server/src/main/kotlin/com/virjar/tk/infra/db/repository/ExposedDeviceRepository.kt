package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.device.DeviceRecord
import com.virjar.tk.domain.device.DeviceRepository
import com.virjar.tk.infra.db.Devices
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedDeviceRepository : DeviceRepository {
    override fun getDevices(uid: String): List<DeviceRecord> {
        return transaction {
            Devices.selectAll()
                .where { (Devices.uid eq uid) and (Devices.status eq 1) }
                .orderBy(Devices.lastLogin, SortOrder.DESC)
                .map { it.toDeviceRecord() }
        }
    }
}

private fun ResultRow.toDeviceRecord() = DeviceRecord(
    id = this[Devices.id].value,
    uid = this[Devices.uid],
    deviceId = this[Devices.deviceId],
    deviceName = this[Devices.deviceName],
    deviceModel = this[Devices.deviceModel],
    deviceFlag = this[Devices.deviceFlag],
    lastLogin = this[Devices.lastLogin],
)
