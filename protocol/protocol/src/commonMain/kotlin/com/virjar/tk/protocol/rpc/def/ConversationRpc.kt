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

    /**
     * 群已读列表（MSG-01/F5）：当前活跃成员中已读水位 ≥ [seq] 的投影（姓名 + 水位）。
     * 可见性由服务端裁决：默认仅群主/群管理员与该消息的发送者可调用。水位语义是
     * "阅读进度已到该消息"的近似，不是逐条回执。超过服务端容量时整次请求失败，绝不截断。
     */
    @RpcMethod(6)
    @com.virjar.tk.protocol.SinceProtocol(6)
    suspend fun listChatReadStates(chatId: String, seq: Long): List<com.virjar.tk.protocol.model.ChatReadState>
}
