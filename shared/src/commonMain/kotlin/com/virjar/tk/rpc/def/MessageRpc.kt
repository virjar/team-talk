package com.virjar.tk.rpc.def

import com.virjar.tk.model.Message
import com.virjar.tk.rpc.RpcService

/** 消息服务 RPC IDL。消息发送不走 RPC（MESSAGE 帧+独立 ACK）。⚠️ methodId 稳定。 */
@RpcService("message")
interface MessageRpc {
    /** fromSeq=0 表示从最新开始；倒序窗口。 */
    suspend fun getHistory(chatId: String, fromSeq: Long, limit: Int): List<Message>
    suspend fun search(chatId: String, keyword: String, limit: Int): List<Message>
    suspend fun revoke(chatId: String, serverSeq: Long)
    suspend fun edit(msg: Message)
    suspend fun forward(srcChatId: String, srcSeq: Long, targetChatId: String): Message
    suspend fun markRead(chatId: String, readSeq: Long)
}
