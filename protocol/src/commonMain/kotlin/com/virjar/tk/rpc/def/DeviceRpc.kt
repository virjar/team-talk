package com.virjar.tk.rpc.def

import com.virjar.tk.model.Device
import com.virjar.tk.rpc.RpcMethod
import com.virjar.tk.rpc.RpcService

/**
 * 设备管理 RPC IDL。
 *
 * 每个方法用 @RpcMethod(id) 显式锁定稳定 wire 编号，声明顺序不参与编号。
 */
@RpcService("device")
interface DeviceRpc {
    @RpcMethod(1)
    suspend fun listDevices(): List<Device>

    @RpcMethod(2)
    suspend fun kickDevice(deviceId: String)
}
