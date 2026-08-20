package com.virjar.tk.rpc.def

import com.virjar.tk.model.Conversation
import com.virjar.tk.rpc.RpcMethod
import com.virjar.tk.rpc.RpcService

/** 会话服务 RPC IDL；每个方法显式声明稳定 methodId。 */
@RpcService("conversation")
interface ConversationRpc {
    @RpcMethod(1)
    suspend fun list(): List<Conversation>
    @RpcMethod(2)
    suspend fun sync(afterVersion: Long): List<Conversation>
    @RpcMethod(3)
    suspend fun setDraft(chatId: String, draft: String?)
    @RpcMethod(4)
    suspend fun setPin(chatId: String, pinned: Boolean)
    @RpcMethod(5)
    suspend fun setMute(chatId: String, muted: Boolean)
    @RpcMethod(6)
    suspend fun delete(chatId: String)
}
