package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.model.Device
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/**
 * 设备管理 RPC IDL。
 *
 * 每个方法用 @RpcMethod(id) 显式锁定当前协议基线的 wire 编号，声明顺序不参与编号。
 */
@com.virjar.tk.protocol.SinceProtocol(0)
@RpcService("device")
interface DeviceRpc {
    @RpcMethod(1)
    suspend fun listDevices(): List<Device>

    @RpcMethod(2)
    suspend fun kickDevice(deviceId: String)
}
