package com.virjar.tk.rpc

import com.virjar.tk.protocol.payload.ResponsePayload

/**
 * 远端 RPC 返回的非成功状态。协议层保留原始状态，不依赖客户端 AppError；
 * 各 SDK 可以映射为自己的公开错误模型。
 */
class RpcStatusException(
    val status: Int,
    override val message: String,
) : Exception(message)

fun ResponsePayload.ensureSuccess() {
    if (status == 0) return
    val msg = payload?.decodeToString() ?: "RPC failed (status=$status)"
    throw RpcStatusException(status, msg)
}
