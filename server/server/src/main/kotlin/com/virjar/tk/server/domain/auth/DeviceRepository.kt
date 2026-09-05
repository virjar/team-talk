package com.virjar.tk.server.domain.auth

import com.virjar.tk.protocol.model.Device

/** 认证设备的持久化读取端口，直接返回调用方需要的设备视图。 */
interface DeviceRepository {
    fun getDevices(uid: String): List<Device>
}
