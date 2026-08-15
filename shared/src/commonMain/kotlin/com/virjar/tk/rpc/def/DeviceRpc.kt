package com.virjar.tk.rpc.def

import com.virjar.tk.model.Device
import com.virjar.tk.rpc.RpcMethod
import com.virjar.tk.rpc.RpcService

/**
 * 设备管理 RPC IDL。
 *
 * ⚠️ methodId 稳定性：新方法只追加末尾；中间插入必须 @RpcMethod(id) 显式锁定。
 */
@RpcService("device")
interface DeviceRpc {
    suspend fun listDevices(): List<Device>

    @RpcMethod(2)
    suspend fun kickDevice(deviceId: String)
}
