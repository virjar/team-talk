package com.virjar.tk.rpc.def

import com.virjar.tk.model.Conversation
import com.virjar.tk.rpc.RpcService

/** 会话服务 RPC IDL。⚠️ methodId 稳定：新方法只追加末尾。 */
@RpcService("conversation")
interface ConversationRpc {
    suspend fun list(): List<Conversation>
    suspend fun sync(afterVersion: Long): List<Conversation>
    suspend fun setDraft(chatId: String, draft: String?)
    suspend fun setPin(chatId: String, pinned: Boolean)
    suspend fun setMute(chatId: String, muted: Boolean)
    suspend fun delete(chatId: String)
}
