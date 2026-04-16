package com.virjar.tk.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

data class DeviceRow(
    val id: Long,
    val uid: String,
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val deviceFlag: Int,
    val lastLogin: Long?,
)

object DeviceDao {

    fun upsertDevice(
        uid: String,
        deviceId: String,
        deviceName: String,
        deviceModel: String,
        deviceFlag: Int,
    ): DeviceRow = transaction {
        val existing = Devices.selectAll().where {
            (Devices.uid eq uid) and (Devices.deviceId eq deviceId)
        }.singleOrNull()

        val now = System.currentTimeMillis()

        if (existing != null) {
            Devices.update({
                (Devices.uid eq uid) and (Devices.deviceId eq deviceId)
            }) {
                it[Devices.deviceName] = deviceName
                it[Devices.deviceModel] = deviceModel
                it[Devices.deviceFlag] = deviceFlag
                it[Devices.lastLogin] = now
            }
            existing.toDeviceRow().copy(
                deviceName = deviceName,
                deviceModel = deviceModel,
                deviceFlag = deviceFlag,
                lastLogin = now,
            )
        } else {
            val id = Devices.insert {
                it[Devices.uid] = uid
                it[Devices.deviceId] = deviceId
                it[Devices.deviceName] = deviceName
                it[Devices.deviceModel] = deviceModel
                it[Devices.deviceFlag] = deviceFlag
                it[Devices.lastLogin] = now
            } get Devices.id

            DeviceRow(
                id = id.value,
                uid = uid,
                deviceId = deviceId,
                deviceName = deviceName,
                deviceModel = deviceModel,
                deviceFlag = deviceFlag,
                lastLogin = now,
            )
        }
    }

    fun findByUid(uid: String): List<DeviceRow> = transaction {
        Devices.selectAll().where { Devices.uid eq uid }
            .orderBy(Devices.lastLogin, SortOrder.DESC)
            .map { it.toDeviceRow() }
    }

    fun deleteDevice(uid: String, deviceId: String): Boolean = transaction {
        val count = Devices.deleteWhere {
            with(SqlExpressionBuilder) {
                (Devices.uid eq uid) and (Devices.deviceId eq deviceId)
            }
        }
        count > 0
    }

    private fun ResultRow.toDeviceRow() = DeviceRow(
        id = this[Devices.id].value,
        uid = this[Devices.uid],
        deviceId = this[Devices.deviceId],
        deviceName = this[Devices.deviceName],
        deviceModel = this[Devices.deviceModel],
        deviceFlag = this[Devices.deviceFlag],
        lastLogin = this[Devices.lastLogin],
    )
}
