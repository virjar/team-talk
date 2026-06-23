package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.RpcInvoker
import com.virjar.tk.client.ensureSuccess
import com.virjar.tk.model.Device
import com.virjar.tk.outcome
import com.virjar.tk.protocol.DeviceMethod
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ServiceId

class DeviceRepository(
    private val rpcClient: RpcInvoker,
) {
    suspend fun listDevices(): Outcome<List<Device>> = outcome {
        val response = rpcClient.invoke(ServiceId.DEVICE, DeviceMethod.LIST.id)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome emptyList()
        ProtoCodec.decodeList(Device, data)
    }

    suspend fun kickDevice(deviceId: String): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(deviceId) }
        rpcClient.invoke(ServiceId.DEVICE, DeviceMethod.KICK.id, payload).ensureSuccess()
    }
}
