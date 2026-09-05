package com.virjar.tk.protocol.rpc

import com.virjar.tk.protocol.payload.ResponsePayload

/**
 * RPC 调用抽象。RpcClient 实现此接口；生成的 XxxRpcProxy 通过它发起调用。
 *
 * service 参数为字符串 serviceId（@RpcService name，wire 直传字符串）。
 */
interface RpcInvoker {
    /** 真实连接实现必须提供本连接协商值；纯本地夹具默认当前基线。 */
    val negotiatedProtocolVersion: com.virjar.tk.protocol.ProtocolVersion
        get() = com.virjar.tk.protocol.ProtocolVersions.CURRENT
    suspend fun invoke(service: String, methodId: Int, payload: ByteArray? = null): ResponsePayload
}

class RpcProtocolUnavailableException : IllegalStateException("RPC is unavailable under the negotiated protocol version") {
    val status: Int = 426
}
