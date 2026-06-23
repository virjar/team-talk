package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.device.DeviceRepository
import com.virjar.tk.domain.device.toModel
import com.virjar.tk.protocol.*

class DeviceRouteHandler(
    private val deviceRepo: DeviceRepository,
    private val authService: AuthService,
) {
    fun route(uid: String, methodId: Int, payload: ByteArray?): ByteArray {
        return when (DeviceMethod.fromId(methodId)) {
            DeviceMethod.LIST -> {
                ProtoCodec.encodeList(deviceRepo.getDevices(uid).map { it.toModel() })
            }
            DeviceMethod.KICK -> ProtoCodec.withPayload(payload!!) {
                val deviceId = readString()!!
                deviceRepo.kickDevice(uid, deviceId)
                authService.kickDevice(uid, deviceId)
                ByteArray(0)
            }
        }
    }
}
