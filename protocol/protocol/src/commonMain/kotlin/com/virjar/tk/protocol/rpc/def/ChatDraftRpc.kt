package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

@SinceProtocol(2)
@RpcService("chatDraft")
interface ChatDraftRpc {
    @RpcMethod(1)
    suspend fun get(chatId: String): ChatDraftSnapshot
    @RpcMethod(2)
    suspend fun mutate(command: ChatDraftCommand): ChatDraftMutationResult
}
