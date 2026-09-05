package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.model.ConversationPage
import com.virjar.tk.protocol.model.ConversationPageRequest
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** 会话服务 RPC IDL；每个方法显式声明当前协议基线的 methodId。 */
@com.virjar.tk.protocol.SinceProtocol(0)
@RpcService("conversation")
interface ConversationRpc {
    @RpcMethod(1)
    suspend fun listPage(request: ConversationPageRequest): ConversationPage
    @RpcMethod(2)
    suspend fun setDraft(chatId: String, draft: String?)
    @RpcMethod(3)
    suspend fun setPin(chatId: String, pinned: Boolean)
    @RpcMethod(4)
    suspend fun setMute(chatId: String, muted: Boolean)
    @RpcMethod(5)
    suspend fun delete(chatId: String)
}
