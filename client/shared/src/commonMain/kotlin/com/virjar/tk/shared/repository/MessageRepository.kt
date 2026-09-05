package com.virjar.tk.shared.repository

import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.rpc.gen.MessageRpcProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MessageRepository internal constructor(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
    private val onPendingMirrorCommitted: () -> Unit,
    private val onOutgoingProjectionMayHaveChanged: () -> Unit = {},
) {
    constructor(
        rpcClient: RpcInvoker,
        localCache: LocalCache,
    ) : this(
        rpcClient = rpcClient,
        localCache = localCache,
        onPendingMirrorCommitted = {},
        onOutgoingProjectionMayHaveChanged = {},
    )

    private val rpc = MessageRpcProxy(rpcClient)
    private val readMirrorLocks = Array(READ_MIRROR_LOCK_STRIPES) { Mutex() }

    /** 拉取历史并写入本地缓存（本地优先）。 */
    suspend fun getHistory(chatId: String, fromSeq: Long = 0, limit: Int = 10): Outcome<List<Message>> = outcome {
        require(chatId.isNotBlank()) { "消息历史 chatId 不能为空" }
        require(fromSeq >= 0L) { "消息历史起始序号不能为负数" }
        requireMessageQueryPageLimit(limit)
        val lease = localCache.beginMessageHistoryLease(
            chatId = chatId,
            resetResidentWindow = fromSeq == 0L,
        )
        var applied = false
        try {
            val page = rpc.getHistory(chatId, fromSeq, limit)
            currentCoroutineContext().ensureActive()
            check(page.size <= limit) {
                "消息历史响应 ${page.size} 条，超过请求上限 $limit"
            }
            if (!localCache.applyMessageHistoryPage(lease, page)) {
                throw CancellationException(
                    "Message history request was superseded for chat $chatId",
                )
            }
            applied = true
            onOutgoingProjectionMayHaveChanged()
            page
        } finally {
            if (!applied) {
                localCache.abandonMessageHistoryLease(lease)
            }
        }
    }

    suspend fun revokeMessage(chatId: String, serverSeq: Long): Outcome<Unit> = outcome { rpc.revoke(chatId, serverSeq) }

    /**
     * 添加当前用户的一个回应。服务端 row-keyed 幂等：重复点击/重试都安全；
     * 状态更新由服务端事件回环（含本端）收敛，不依赖本方法的返回快照。
     */
    suspend fun addReaction(chatId: String, serverSeq: Long, emoji: String): Outcome<Unit> = outcome {
        rpc.addReaction(chatId, serverSeq, emoji)
    }

    suspend fun removeReaction(chatId: String, serverSeq: Long, emoji: String): Outcome<Unit> = outcome {
        rpc.removeReaction(chatId, serverSeq, emoji)
    }

    /**
     * 拉取 [fromSeq, toSeq] 闭区间的服务端权威回应聚合并原子替换本地投影。
     * 客户端在历史窗口加载后调用；断网时缓存行照常展示（stale 投影）。
     */
    suspend fun loadReactions(chatId: String, fromSeq: Long, toSeq: Long): Outcome<Unit> = outcome {
        require(fromSeq in 1..toSeq) { "回应聚合区间非法" }
        // 一次实时 delta 可能恰好作废首次历史补齐。立即重拉一次，让静止窗口也能补全；
        // 持续变化时交还调用方，不能把聊天活动变成无界 RPC 循环。
        repeat(2) {
            currentCoroutineContext().ensureActive()
            val lease = localCache.beginMessageReactionSnapshot(chatId)
            try {
                val summaries = rpc.listReactions(chatId, fromSeq, toSeq)
                currentCoroutineContext().ensureActive()
                if (localCache.applyMessageReactionSnapshot(lease, chatId, fromSeq, toSeq, summaries)) {
                    return@outcome
                }
            } finally {
                localCache.abandonProjectionSnapshot(lease)
            }
        }
        throw CancellationException("Message reaction snapshot was superseded for chat $chatId")
    }
    suspend fun editMessage(message: Message): Outcome<Unit> = outcome { rpc.edit(message) }

    suspend fun forwardMessage(srcChatId: String, srcSeq: Long, targetChatId: String): Outcome<Message> = outcome {
        rpc.forward(srcChatId, srcSeq, targetChatId)
    }

    /**
     * 把一条已确认消息复制保存到"保存的消息"私有会话。[operationId] 是本命令的稳定 UUID，
     * 丢响应重试必须复用同值：服务端按 chatId+operationId+内容 hash 幂等返回原副本。
     */
    suspend fun saveMessage(
        srcChatId: String,
        srcSeq: Long,
        operationId: String,
    ): Outcome<Message> = outcome {
        rpc.saveMessage(srcChatId, srcSeq, operationId)
    }

    suspend fun searchMessages(chatId: String, keyword: String, limit: Int = 10): Outcome<List<Message>> = outcome {
        requireMessageQueryPageLimit(limit)
        rpc.search(chatId, keyword, limit)
    }

    /**
     * 在任何网络挂起之前推进本地投影与持久 outbox。
     * 返回的水位对本会话单调递增，且可能高于 [readSeq]。
     */
    fun markReadLocal(chatId: String, readSeq: Long): Long {
        val watermark = localCache.enqueueConversationRead(chatId, readSeq)
        onPendingMirrorCommitted()
        return watermark
    }

    /**
     * 镜像 [chatId] 最新的待处理水位。有条件的确认不能
     * 移除在这笔 RPC 飞行期间产生的更新水位。
     */
    suspend fun mirrorRead(chatId: String): Outcome<Unit> = readMirrorLock(chatId).withLock {
        val pending = localCache.getPendingConversationRead(chatId)
            ?: return@withLock Outcome.Success(Unit)
        outcome {
            rpc.markRead(chatId, pending.readSeq)
            localCache.markConversationReadMirrored(chatId, pending.readSeq)
        }
    }

    /** 在初次认证以及每次重连之后重试持久化的已读水位。 */
    suspend fun retryPendingReads(): Outcome<Unit> = retryPendingMirrors(
        snapshot = localCache.getPendingConversationReads(),
    ) { pending -> mirrorRead(pending.chatId) }

    /** 面向希望等待镜像尝试的 SDK 调用方的本地优先便捷方法。 */
    suspend fun markRead(chatId: String, readSeq: Long): Outcome<Unit> {
        markReadLocal(chatId, readSeq)
        return mirrorRead(chatId)
    }

    private fun readMirrorLock(chatId: String): Mutex =
        readMirrorLocks[(chatId.hashCode() and Int.MAX_VALUE) % readMirrorLocks.size]

    private companion object {
        const val READ_MIRROR_LOCK_STRIPES = 32
    }
}

private fun requireMessageQueryPageLimit(limit: Int) {
    require(limit in 1..Message.MAX_QUERY_PAGE_SIZE) {
        "消息查询分页大小必须在 1..${Message.MAX_QUERY_PAGE_SIZE} 之间"
    }
}
