package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.model.Device
import com.virjar.tk.server.domain.auth.DeviceRepository
import com.virjar.tk.server.infra.db.Devices
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedDeviceRepository(
    private val database: Database,
) : DeviceRepository {
    override fun getDevices(uid: String): List<Device> {
        return transaction(database) {
            Devices.selectAll()
                .where { (Devices.uid eq uid) and (Devices.status eq 1) }
                .orderBy(Devices.lastLogin, SortOrder.DESC)
                .map { it.toDevice() }
        }
    }
}

private fun ResultRow.toDevice() = Device(
    deviceId = this[Devices.deviceId],
    deviceName = this[Devices.deviceName],
    deviceModel = this[Devices.deviceModel],
    deviceFlag = this[Devices.deviceFlag],
    lastLogin = this[Devices.lastLogin],
)
