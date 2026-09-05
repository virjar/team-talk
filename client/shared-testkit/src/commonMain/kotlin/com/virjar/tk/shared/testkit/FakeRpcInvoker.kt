package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.AppError
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.payload.ResponsePayload

/**
 * Fake [RpcInvoker]，用于 Repository 测试。
 *
 * 用法：
 * ```
 * val rpc = FakeRpcInvoker()
 * rpc.enqueueOk(encodedMessages)  // 预设成功响应
 * rpc.enqueueError(401, "token expired")  // 预设认证失效
 * rpc.throwOnInvoke = AppError.Network  // 模拟已经分类的网络失败
 * ```
 */
class FakeRpcInvoker : RpcInvoker {
    private val responses = ArrayDeque<ResponsePayload>()

    /** 非空时 invoke 抛出此异常；Repository 级网络失败直接注入 [AppError.Network]。 */
    var throwOnInvoke: Throwable? = null

    /** 记录所有 invoke 调用（service, methodId, payload），用于断言。 */
    val calls = mutableListOf<Triple<String, Int, ByteArray?>>()

    fun enqueue(response: ResponsePayload) {
        responses.addLast(response)
    }

    fun enqueueOk(payload: ByteArray? = null) {
        enqueue(ResponsePayload(requestId = calls.size + 1, status = 0, payload = payload))
    }

    fun enqueueError(status: Int, msg: String = "error") {
        enqueue(ResponsePayload(requestId = calls.size + 1, status = status, payload = msg.encodeToByteArray()))
    }

    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
        calls += Triple(service, methodId, payload)
        throwOnInvoke?.let { throw it }
        return responses.removeFirstOrNull()
            ?: error("FakeRpcInvoker: no more preset responses for $service/$methodId")
    }
}
