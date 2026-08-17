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

    /**
     * 保存/清除草稿（null = 清除）。
     *
     * 本地立即生效（草稿是纯客户端状态，服务端只是跨设备镜像）：清除若只等
     * CONVERSATION_UPDATED 回环，会被本地缓存「draft 非空优先」合并策略挡回，
     * 表现为发送后列表仍显示 [草稿] 且重进会话回填旧草稿。
     */
    suspend fun setDraft(chatId: String, draft: String?): Outcome<Unit> = outcome {
        rpc.setDraft(chatId, draft)
        localCache.setConversationDraft(chatId, draft)
    }
    suspend fun setPin(chatId: String, pinned: Boolean): Outcome<Unit> = outcome { rpc.setPin(chatId, pinned) }
    suspend fun setMute(chatId: String, muted: Boolean): Outcome<Unit> = outcome { rpc.setMute(chatId, muted) }
    suspend fun deleteConversation(chatId: String): Outcome<Unit> = outcome {
        rpc.delete(chatId)
        localCache.deleteConversation(chatId)
    }
}
