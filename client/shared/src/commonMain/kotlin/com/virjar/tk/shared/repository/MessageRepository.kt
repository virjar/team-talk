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

    /** 读取精确引用，不改变常驻历史窗口或其分页租约。 */
    suspend fun getMessage(chatId: String, serverSeq: Long): Outcome<Message?> = outcome {
        require(chatId.isNotBlank() && serverSeq > 0L) { "Message reference requires a chat and confirmed sequence" }
        val page = rpc.getHistory(chatId, serverSeq, 1)
        currentCoroutineContext().ensureActive()
        check(page.size <= 1) { "Message reference response exceeded its page budget" }
        page.singleOrNull()?.takeIf { it.chatId == chatId && it.serverSeq == serverSeq }
    }

    /**
     * 独立查询服务器的倒序历史页；[fromSeq] 包含起点，0 表示最新页。
     *
     * 不依赖或创建 UI 分页器，不写本地缓存、常驻窗口或已读证明。每页可使用自己的明确游标；
     * 查询期间的实时编辑、撤回和新打开窗口保持其本地状态。需要填充 UI 历史窗口时使用 [getHistory]。
     */
    suspend fun queryHistory(chatId: String, fromSeq: Long = 0, limit: Int = 10): Outcome<List<Message>> = outcome {
        require(chatId.isNotBlank()) { "消息历史 chatId 不能为空" }
        require(fromSeq >= 0L) { "消息历史起始序号不能为负数" }
        requireMessageQueryPageLimit(limit)
        val page = rpc.getHistory(chatId, fromSeq, limit)
        currentCoroutineContext().ensureActive()
        check(page.size <= limit) { "消息历史响应超过请求上限 $limit" }
        check(page.all { it.chatId == chatId && it.serverSeq > 0 && (fromSeq == 0L || it.serverSeq <= fromSeq) }) {
            "消息历史响应包含窗口外消息"
        }
        check(page.zipWithNext().all { (newer, older) -> newer.serverSeq > older.serverSeq } &&
            page.all { it.clientMsgId.isNotBlank() } && page.map { it.clientMsgId }.distinct().size == page.size) {
            "消息历史响应顺序或身份无效"
        }
        page
    }

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

    /**
     * 独立查询以 [rootSeq] 为根的线程回复（倒序窗口，[fromSeq] 包含起点，0 表示最新页）。
     *
     * 不写本地缓存、常驻窗口或已读证明；线程内计数徽标用 [LocalCache.threadReplyCounts]
     * 本地推导，点开线程以本查询的权威结果为准。
     */
    suspend fun queryThreadMessages(
        chatId: String,
        rootSeq: Long,
        fromSeq: Long = 0,
        limit: Int = 10,
    ): Outcome<List<Message>> = outcome {
        require(chatId.isNotBlank()) { "线程查询 chatId 不能为空" }
        require(rootSeq > 0L) { "线程根必须是已分配的服务端序号" }
        require(fromSeq >= 0L) { "线程查询起始序号不能为负数" }
        requireMessageQueryPageLimit(limit)
        val page = rpc.getThreadMessages(chatId, rootSeq, fromSeq, limit)
        currentCoroutineContext().ensureActive()
        check(page.size <= limit) { "线程查询响应超过请求上限 $limit" }
        check(page.all {
            it.chatId == chatId && it.serverSeq > 0 && it.serverSeq != rootSeq &&
                it.messageType == com.virjar.tk.protocol.MessageType.REPLY.code &&
                (fromSeq == 0L || it.serverSeq <= fromSeq)
        }) { "线程查询响应包含窗口外、根自身或非回复消息" }
        check(page.zipWithNext().all { (newer, older) -> newer.serverSeq > older.serverSeq } &&
            page.map { it.clientMsgId }.distinct().size == page.size) {
            "线程查询响应顺序或身份无效"
        }
        page
    }

    suspend fun revokeMessage(chatId: String, serverSeq: Long): Outcome<Unit> = outcome { rpc.revoke(chatId, serverSeq) }

    /**
     * 定时发送（MSG-01/F3）：登记一条到点投递的消息。[deliverAt] 必须晚于当前时间且不超
     * 30 天；[operationId] 是稳定幂等身份，重试复用同值返回原调度记录。调度时只做成员与
     * 基础校验；到点投递走完整发送管道，附件失效等失败置 FAILED 保留在待发列表。
     */
    suspend fun scheduleMessage(
        chatId: String,
        messageType: Int,
        body: com.virjar.tk.protocol.body.MessageBody,
        deliverAt: Long,
        operationId: String,
    ): Outcome<com.virjar.tk.protocol.model.ScheduledMessage> = outcome {
        require(chatId.isNotBlank()) { "定时发送 chatId 不能为空" }
        require(operationId.isNotBlank() && operationId.length <= 64) { "定时发送 operationId 非法" }
        require(deliverAt > currentEpochMillis()) { "投递时间必须晚于当前时间" }
        require(deliverAt <= currentEpochMillis() + MAX_SCHEDULE_HORIZON_MILLIS) { "投递时间不能超过 30 天" }
        val scheduled = rpc.scheduleMessage(
            com.virjar.tk.protocol.model.ScheduledMessageDraft(
                chatId = chatId,
                messageType = messageType,
                deliverAt = deliverAt,
                operationId = operationId,
                body = body,
            ),
        )
        currentCoroutineContext().ensureActive()
        check(scheduled.scheduledId.isNotBlank() && scheduled.chatId == chatId) {
            "定时发送响应身份无效"
        }
        scheduled
    }

    /** 取消一条仍处 WAITING 的定时消息；仅发起者可取消，终态重复取消幂等成功。 */
    suspend fun cancelScheduledMessage(scheduledId: String, operationId: String): Outcome<Boolean> = outcome {
        require(scheduledId.isNotBlank() && operationId.isNotBlank()) { "取消参数不能为空" }
        rpc.cancelScheduledMessage(scheduledId, operationId)
    }

    /** 当前账号在某会话的待发定时消息（WAITING 与 FAILED，deliverAt 升序）。 */
    suspend fun listScheduledMessages(chatId: String): Outcome<List<com.virjar.tk.protocol.model.ScheduledMessage>> = outcome {
        require(chatId.isNotBlank()) { "定时消息列表 chatId 不能为空" }
        val list = rpc.listScheduledMessages(chatId)
        currentCoroutineContext().ensureActive()
        check(list.all { it.chatId == chatId && it.scheduledId.isNotBlank() }) { "定时消息列表响应身份无效" }
        list
    }


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
     * [readSeq] 必须属于该 chat 已知的确认消息范围；越界不会截成全部已读。
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

/** 定时发送的最大投递跨度；与服务端 horizon 校验一致。 */
private const val MAX_SCHEDULE_HORIZON_MILLIS = 30L * 24 * 60 * 60 * 1000

private fun currentEpochMillis(): Long =
    com.virjar.tk.shared.platform.platformCurrentTimeMillis()

private fun requireMessageQueryPageLimit(limit: Int) {
    require(limit in 1..Message.MAX_QUERY_PAGE_SIZE) {
        "消息查询分页大小必须在 1..${Message.MAX_QUERY_PAGE_SIZE} 之间"
    }
}
