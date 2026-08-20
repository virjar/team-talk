package com.virjar.tk.rpc.def

import com.virjar.tk.model.Message
import com.virjar.tk.rpc.RpcMethod
import com.virjar.tk.rpc.RpcService

/** 消息服务 RPC IDL。消息发送不走 RPC（MESSAGE 帧+独立 ACK）；methodId 全部显式声明。 */
@RpcService("message")
interface MessageRpc {
    /** fromSeq=0 表示从最新开始；倒序窗口。 */
    @RpcMethod(1)
    suspend fun getHistory(chatId: String, fromSeq: Long, limit: Int): List<Message>
    @RpcMethod(2)
    suspend fun search(chatId: String, keyword: String, limit: Int): List<Message>
    @RpcMethod(3)
    suspend fun revoke(chatId: String, serverSeq: Long)
    @RpcMethod(4)
    suspend fun edit(msg: Message)
    @RpcMethod(5)
    suspend fun forward(srcChatId: String, srcSeq: Long, targetChatId: String): Message
    @RpcMethod(6)
    suspend fun markRead(chatId: String, readSeq: Long)
}
