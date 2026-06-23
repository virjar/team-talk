package com.virjar.tk.testing

import com.virjar.tk.client.RpcInvoker
import com.virjar.tk.protocol.ServiceId
import com.virjar.tk.protocol.payload.ResponsePayload

/**
 * Fake [RpcInvoker]，用于 Repository 测试。
 *
 * 用法：
 * ```
 * val rpc = FakeRpcInvoker()
 * rpc.enqueueOk(encodedMessages)  // 预设成功响应
 * rpc.enqueueError(401, "token expired")  // 预设认证失效
 * rpc.throwOnInvoke = IllegalStateException("Not connected")  // 模拟网络异常
 * ```
 */
class FakeRpcInvoker : RpcInvoker {
    private val responses = ArrayDeque<ResponsePayload>()

    /** 非空时，invoke 抛出此异常（模拟网络断开等）。 */
    var throwOnInvoke: Throwable? = null

    /** 记录所有 invoke 调用（serviceId, methodId, payload），用于断言。 */
    val calls = mutableListOf<Triple<ServiceId, Int, ByteArray?>>()

    fun enqueue(response: ResponsePayload) {
        responses.addLast(response)
    }

    fun enqueueOk(payload: ByteArray? = null) {
        enqueue(ResponsePayload(requestId = calls.size + 1, status = 0, payload = payload))
    }

    fun enqueueError(status: Int, msg: String = "error") {
        enqueue(ResponsePayload(requestId = calls.size + 1, status = status, payload = msg.encodeToByteArray()))
    }

    override suspend fun invoke(serviceId: ServiceId, methodId: Int, payload: ByteArray?): ResponsePayload {
        calls += Triple(serviceId, methodId, payload)
        throwOnInvoke?.let { throw it }
        return responses.removeFirstOrNull()
            ?: error("FakeRpcInvoker: no more preset responses for $serviceId/$methodId")
    }
}
