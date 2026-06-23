package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.RpcInvoker
import com.virjar.tk.client.ensureSuccess
import com.virjar.tk.model.Message
import com.virjar.tk.outcome
import com.virjar.tk.protocol.MessageMethod
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ServiceId

class MessageRepository(
    private val rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    /**
     * 拉取历史消息。成功时把服务端数据写入 LocalCache。
     * 失败时返回 Failure，调用方可 `.recover { localCache.getMessages(chatId, limit) }` 降级。
     */
    suspend fun getHistory(chatId: String, fromSeq: Long = 0, limit: Int = 50): Outcome<List<Message>> = outcome {
        val payload = ProtoCodec.encodePayload {
            writeString(chatId)
            writeVarLong(fromSeq)
            writeVarInt(limit)
        }
        val response = rpcClient.invoke(ServiceId.MESSAGE, MessageMethod.GET_HISTORY.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome emptyList()
        val messages = ProtoCodec.decodeList(Message, data)
        messages.forEach { localCache.insertMessage(it) }
        messages
    }

    suspend fun revokeMessage(chatId: String, serverSeq: Long): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId); writeVarLong(serverSeq) }
        rpcClient.invoke(ServiceId.MESSAGE, MessageMethod.REVOKE.id, payload).ensureSuccess()
    }

    suspend fun editMessage(message: Message): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encode(message)
        rpcClient.invoke(ServiceId.MESSAGE, MessageMethod.EDIT.id, payload).ensureSuccess()
    }

    suspend fun forwardMessage(srcChatId: String, srcSeq: Long, targetChatId: String): Outcome<Message> = outcome {
        val payload = ProtoCodec.encodePayload {
            writeString(srcChatId)
            writeVarLong(srcSeq)
            writeString(targetChatId)
        }
        val response = rpcClient.invoke(ServiceId.MESSAGE, MessageMethod.FORWARD.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: error("forward: empty payload")
        ProtoCodec.decode(Message, data)
    }

    suspend fun searchMessages(chatId: String, keyword: String, limit: Int = 20): Outcome<List<Message>> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId); writeString(keyword); writeVarInt(limit) }
        val response = rpcClient.invoke(ServiceId.MESSAGE, MessageMethod.SEARCH.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome emptyList()
        ProtoCodec.decodeList(Message, data)
    }

    suspend fun markRead(chatId: String, readSeq: Long): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId); writeVarLong(readSeq) }
        rpcClient.invoke(ServiceId.MESSAGE, MessageMethod.MARK_READ.id, payload).ensureSuccess()
    }
}
