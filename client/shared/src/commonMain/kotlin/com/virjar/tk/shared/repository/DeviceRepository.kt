package com.virjar.tk.shared.repository

import com.virjar.tk.shared.Outcome
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.model.Device
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.rpc.gen.DeviceRpcProxy

class DeviceRepository(rpcClient: RpcInvoker) {
    private val rpc = DeviceRpcProxy(rpcClient)

    suspend fun listDevices(): Outcome<List<Device>> = outcome { rpc.listDevices() }
    suspend fun kickDevice(deviceId: String): Outcome<Unit> = outcome { rpc.kickDevice(deviceId) }

    suspend fun setXiaomiPushRegistration(
        registrationId: String,
        packageName: String,
        deploymentFingerprint: String,
    ): Outcome<Boolean> = outcome {
        rpc.setXiaomiPushRegistration(registrationId, packageName, deploymentFingerprint)
    }
}
