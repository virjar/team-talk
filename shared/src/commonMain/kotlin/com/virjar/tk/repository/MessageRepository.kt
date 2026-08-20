package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.MessageSender
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.model.Message
import com.virjar.tk.outcome
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.rpc.gen.MessageRpcProxy

class MessageRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
    private val messageSender: MessageSender,
) {
    private val rpc = MessageRpcProxy(rpcClient)

    /** 发送消息（直达连接层，等待服务端 ACK）——唯一不走 RPC 的写操作。 */
    suspend fun send(message: Message): Outcome<MessageAckPayload> = outcome {
        messageSender.sendAndWaitAck(message)
    }

    /** 拉取历史并写入本地缓存（本地优先）。 */
    suspend fun getHistory(chatId: String, fromSeq: Long = 0, limit: Int = 10): Outcome<List<Message>> = outcome {
        rpc.getHistory(chatId, fromSeq, limit).also { page ->
            localCache.insertMessagePage(
                chatId = chatId,
                messages = page,
                resetResidentWindow = fromSeq == 0L,
            )
        }
    }

    suspend fun revokeMessage(chatId: String, serverSeq: Long): Outcome<Unit> = outcome { rpc.revoke(chatId, serverSeq) }
    suspend fun editMessage(message: Message): Outcome<Unit> = outcome { rpc.edit(message) }

    suspend fun forwardMessage(srcChatId: String, srcSeq: Long, targetChatId: String): Outcome<Message> = outcome {
        rpc.forward(srcChatId, srcSeq, targetChatId)
    }

    suspend fun searchMessages(chatId: String, keyword: String, limit: Int = 10): Outcome<List<Message>> = outcome {
        rpc.search(chatId, keyword, limit)
    }

    suspend fun markRead(chatId: String, readSeq: Long): Outcome<Unit> = outcome { rpc.markRead(chatId, readSeq) }
}
