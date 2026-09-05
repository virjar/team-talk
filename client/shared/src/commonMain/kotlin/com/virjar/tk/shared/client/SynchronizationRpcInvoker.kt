package com.virjar.tk.shared.client

import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.SyncRpcContract

/** 只交给检查点加载器的窄能力，仅在连接同步期间可用。 */
internal class SynchronizationRpcInvoker(
    private val rpcClient: RpcClient,
    private val requestAdmission: SessionOutboundLease,
) : RpcInvoker {
    override val negotiatedProtocolVersion get() = rpcClient.negotiatedProtocolVersion

    override suspend fun invoke(
        service: String,
        methodId: Int,
        payload: ByteArray?,
    ): ResponsePayload {
        require(service == SyncRpcContract.SERVICE) {
            "Synchronization RPC cannot invoke service=$service"
        }
        return rpcClient.invokeDuringSynchronization(
            service,
            methodId,
            payload,
            requestAdmission,
        )
    }
}
