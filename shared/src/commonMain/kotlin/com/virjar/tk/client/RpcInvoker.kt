package com.virjar.tk.client

import com.virjar.tk.protocol.ServiceId
import com.virjar.tk.protocol.payload.ResponsePayload

/**
 * RPC 调用抽象。RpcClient 实现此接口。
 *
 * Repository 依赖此接口而非具体 [RpcClient]，便于测试时注入 Fake。
 */
interface RpcInvoker {
    suspend fun invoke(serviceId: ServiceId, methodId: Int, payload: ByteArray? = null): ResponsePayload
}
