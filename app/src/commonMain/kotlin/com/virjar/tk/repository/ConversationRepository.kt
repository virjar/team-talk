package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.RpcInvoker
import com.virjar.tk.client.ensureSuccess
import com.virjar.tk.model.Conversation
import com.virjar.tk.outcome
import com.virjar.tk.protocol.ConversationMethod
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ServiceId

class ConversationRepository(
    private val rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    /** 拉取会话列表，成功时写入 LocalCache。 */
    suspend fun listConversations(): Outcome<List<Conversation>> = outcome {
        val payload = ProtoCodec.encodePayload {}
        val response = rpcClient.invoke(ServiceId.CONVERSATION, ConversationMethod.LIST.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome emptyList()
        val conversations = ProtoCodec.decodeList(Conversation, data)
        conversations.forEach { localCache.upsertConversation(it) }
        conversations
    }

    suspend fun setDraft(chatId: String, draft: String?): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId); writeString(draft) }
        rpcClient.invoke(ServiceId.CONVERSATION, ConversationMethod.SET_DRAFT.id, payload).ensureSuccess()
    }

    suspend fun setPin(chatId: String, pinned: Boolean): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId); writeVarInt(if (pinned) 1 else 0) }
        rpcClient.invoke(ServiceId.CONVERSATION, ConversationMethod.SET_PIN.id, payload).ensureSuccess()
    }

    suspend fun setMute(chatId: String, muted: Boolean): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId); writeVarInt(if (muted) 1 else 0) }
        rpcClient.invoke(ServiceId.CONVERSATION, ConversationMethod.SET_MUTE.id, payload).ensureSuccess()
    }

    suspend fun deleteConversation(chatId: String): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId) }
        rpcClient.invoke(ServiceId.CONVERSATION, ConversationMethod.DELETE.id, payload).ensureSuccess()
        localCache.deleteConversation(chatId)
    }
}
