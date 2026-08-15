package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.RpcInvoker
import com.virjar.tk.model.Conversation
import com.virjar.tk.outcome
import com.virjar.tk.rpc.gen.ConversationRpcProxy

class ConversationRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    private val rpc = ConversationRpcProxy(rpcClient)

    suspend fun listConversations(): Outcome<List<Conversation>> = outcome {
        rpc.list().also { list -> list.forEach { localCache.upsertConversation(it) } }
    }

    suspend fun setDraft(chatId: String, draft: String?): Outcome<Unit> = outcome { rpc.setDraft(chatId, draft) }
    suspend fun setPin(chatId: String, pinned: Boolean): Outcome<Unit> = outcome { rpc.setPin(chatId, pinned) }
    suspend fun setMute(chatId: String, muted: Boolean): Outcome<Unit> = outcome { rpc.setMute(chatId, muted) }
    suspend fun deleteConversation(chatId: String): Outcome<Unit> = outcome {
        rpc.delete(chatId)
        localCache.deleteConversation(chatId)
    }
}
