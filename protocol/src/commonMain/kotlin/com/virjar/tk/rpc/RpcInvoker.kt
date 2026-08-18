package com.virjar.tk.rpc

import com.virjar.tk.protocol.payload.ResponsePayload

/**
 * RPC 调用抽象。RpcClient 实现此接口；生成的 XxxRpcProxy 通过它发起调用。
 *
 * service 参数为字符串 serviceId（@RpcService name，协议 v2 起 wire 直传字符串）。
 */
interface RpcInvoker {
    suspend fun invoke(service: String, methodId: Int, payload: ByteArray? = null): ResponsePayload
}
