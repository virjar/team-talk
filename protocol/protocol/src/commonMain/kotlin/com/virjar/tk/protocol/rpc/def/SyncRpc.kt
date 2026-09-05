package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.model.ConversationPage
import com.virjar.tk.protocol.model.SyncCheckpointChatPage
import com.virjar.tk.protocol.model.SyncCheckpointContactPage
import com.virjar.tk.protocol.model.SyncCheckpointHeader
import com.virjar.tk.protocol.model.SyncCheckpointPageRequest
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** Checkpoint 引导 RPC；它是同步期间唯一被接纳的 INVOKE service。 */
@com.virjar.tk.protocol.SinceProtocol(0)
@RpcService("sync")
interface SyncRpc {
    @RpcMethod(1)
    suspend fun beginCheckpoint(datasetId: String): SyncCheckpointHeader

    @RpcMethod(2)
    suspend fun listCheckpointContacts(request: SyncCheckpointPageRequest): SyncCheckpointContactPage

    @RpcMethod(3)
    suspend fun listCheckpointChats(request: SyncCheckpointPageRequest): SyncCheckpointChatPage

    @RpcMethod(4)
    suspend fun listCheckpointConversations(request: SyncCheckpointPageRequest): ConversationPage
}
