package com.virjar.tk.api

import com.virjar.tk.service.DeviceService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.deviceRoutes(deviceService: DeviceService) {
    route("/api/v1/devices") {
        authenticate("auth-jwt") {
            // 获取当前用户所有设备
            get {
                val uid = call.requireUid()
                val devices = deviceService.getDevices(uid)
                call.respond(devices)
            }

            // 踢下指定设备
            delete("/{deviceId}") {
                val uid = call.requireUid()
                val targetDeviceId = call.parameters["deviceId"]
                    ?: throw BusinessException(400, "deviceId required")
                deviceService.kickDevice(uid, targetDeviceId)
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
