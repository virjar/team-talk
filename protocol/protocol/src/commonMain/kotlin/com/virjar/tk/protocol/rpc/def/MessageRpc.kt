package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.MessageReactionSummary
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** 消息服务 RPC IDL。消息发送不走 RPC（MESSAGE 帧+独立 ACK）；methodId 全部显式声明。 */
@com.virjar.tk.protocol.SinceProtocol(0)
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

    /** 对一条已落库消息添加当前用户的回应；重复添加幂等成功。 */
    @RpcMethod(7)
    suspend fun addReaction(chatId: String, serverSeq: Long, emoji: String)

    /** 移除当前用户的一个回应；不存在时幂等成功。 */
    @RpcMethod(8)
    suspend fun removeReaction(chatId: String, serverSeq: Long, emoji: String)

    /**
     * 拉取 [fromSeq, toSeq] 完整闭区间内的权威回应聚合。未返回的消息表示没有回应；
     * 空列表表示整个区间没有回应。超过服务端容量时整次请求失败，绝不返回截断的成功快照。
     */
    @RpcMethod(9)
    suspend fun listReactions(chatId: String, fromSeq: Long, toSeq: Long): List<MessageReactionSummary>

    /**
     * 把一条已确认消息复制保存到当前用户的"保存的消息"私有会话。
     * [operationId] 是本命令的稳定 UUID，重试复用同值即返回原消息，不产生第二条。
     */
    @RpcMethod(10)
    suspend fun saveMessage(srcChatId: String, srcSeq: Long, operationId: String): Message
}
