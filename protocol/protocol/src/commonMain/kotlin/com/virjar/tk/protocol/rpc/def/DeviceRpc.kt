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

    /**
     * 当前已认证 Android 安装的厂商通知注册；vendor 取设备厂商通道标识
     * （xiaomi/huawei/honor/oppo/vivo/meizu），空 registrationId 注销。
     */
    @com.virjar.tk.protocol.SinceProtocol(2)
    @RpcMethod(3)
    suspend fun setOemPushRegistration(
        vendor: String,
        registrationId: String,
        packageName: String,
        deploymentFingerprint: String,
    ): Boolean

    /**
     * 当前已认证 iOS 安装的 APNs 注册。token 为十六进制；空 token 注销。
     * bundleId 必须匹配部署身份，environment 为 sandbox 或 production，与签名 entitlement 一致。
     * 通知仅提示重新同步，不能作为消息或权限事实。
     */
    @com.virjar.tk.protocol.SinceProtocol(4)
    @RpcMethod(4)
    suspend fun setApnsPushRegistration(
        deviceToken: String,
        bundleId: String,
        environment: String,
        deploymentFingerprint: String,
    ): Boolean
}
