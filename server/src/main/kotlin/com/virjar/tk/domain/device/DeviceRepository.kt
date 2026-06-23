package com.virjar.tk.domain.device

import com.virjar.tk.infra.db.Devices
import com.virjar.tk.model.Device
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class DeviceRepository {

    fun registerDevice(uid: String, deviceId: String, deviceName: String?, deviceModel: String?, deviceFlag: Int) {
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

    fun getDevices(uid: String): List<DeviceRecord> {
        return transaction {
            Devices.selectAll()
                .where { Devices.uid eq uid }
                .orderBy(Devices.lastLogin, SortOrder.DESC)
                .map { it.toDeviceRecord() }
        }
    }

    fun kickDevice(uid: String, deviceId: String) {
        transaction {
            Devices.deleteWhere {
                (Devices.uid eq uid) and (Devices.deviceId eq deviceId)
            }
        }
    }
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

private fun ResultRow.toDeviceRecord() = DeviceRecord(
    id = this[Devices.id].value,
    uid = this[Devices.uid],
    deviceId = this[Devices.deviceId],
    deviceName = this[Devices.deviceName],
    deviceModel = this[Devices.deviceModel],
    deviceFlag = this[Devices.deviceFlag],
    lastLogin = this[Devices.lastLogin],
)

fun DeviceRecord.toModel() = Device(
    deviceId = deviceId,
    deviceName = deviceName,
    deviceModel = deviceModel,
    deviceFlag = deviceFlag,
    lastLogin = lastLogin,
)
