package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.device.DeviceRecord
import com.virjar.tk.domain.device.DeviceRepository
import com.virjar.tk.infra.db.Devices
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedDeviceRepository : DeviceRepository {

    override fun registerDevice(uid: String, deviceId: String, deviceName: String?, deviceModel: String?, deviceFlag: Int) {
        transaction {
            val existing = Devices.selectAll()
                .where { (Devices.uid eq uid) and (Devices.deviceId eq deviceId) }
                .singleOrNull()

            if (existing != null) {
                Devices.update({ (Devices.uid eq uid) and (Devices.deviceId eq deviceId) }) {
                    it[Devices.deviceName] = deviceName
                    it[Devices.deviceModel] = deviceModel
                    it[Devices.deviceFlag] = deviceFlag
                    it[Devices.lastLogin] = System.currentTimeMillis()
                }
            } else {
                Devices.insert {
                    it[Devices.uid] = uid
                    it[Devices.deviceId] = deviceId
                    it[Devices.deviceName] = deviceName
                    it[Devices.deviceModel] = deviceModel
                    it[Devices.deviceFlag] = deviceFlag
                    it[Devices.lastLogin] = System.currentTimeMillis()
                    it[Devices.createdAt] = System.currentTimeMillis()
                }
            }
        }
    }

    override fun getDevices(uid: String): List<DeviceRecord> {
        return transaction {
            Devices.selectAll()
                .where { Devices.uid eq uid }
                .orderBy(Devices.lastLogin, SortOrder.DESC)
                .map { it.toDeviceRecord() }
        }
    }

    override fun kickDevice(uid: String, deviceId: String) {
        transaction {
            Devices.deleteWhere {
                (Devices.uid eq uid) and (Devices.deviceId eq deviceId)
            }
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
