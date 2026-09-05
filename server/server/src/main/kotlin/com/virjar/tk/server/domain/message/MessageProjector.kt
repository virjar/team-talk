package com.virjar.tk.server.domain.message

import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.domain.chat.ChatStore
import com.virjar.tk.server.domain.chat.ManagedChatPolicy
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把已落库消息应用到搜索索引、会话列表和用户同步事件。
 *
 * 消息权威字节与序号在 [MessageRepository]（Rocks）落库，同一命令还会留下一个持久化
 * 投影操作；把该操作推进到 Lucene 搜索索引、PostgreSQL 会话/事件投影并最终删除 outbox
 * 行的全部机制都集中在本类：
 *  - 命令路径的即时排空（[drainPendingForMessageLocked]，调用方持有聊天生命周期闸门）；
 *  - 启动/健康触发的全局恢复（[recoverPendingProjections]，双空观察 + generation 复核）；
 *  - 投影失败后的就绪门（[withProjectionReadyChats]），阻塞新命令直到恢复完成；
 *  - 同一 chat+seq 的分条互斥，串行化并发重试与全局恢复对同一操作的竞争。
 *
 * 由 ServerModule 组装为应用级单例：消息命令与启动/运行期恢复使用同一个投影器，
 * 共享锁与恢复状态。它不负责接收命令，也不写网络；同步事件提交后由事件投递器发送。
 */
class MessageProjector(
    private val messages: MessageRepository,
    private val search: MessageSearch,
    private val unitOfWork: PgUnitOfWork,
    private val projectionRepository: MessageProjectionRepository,
    private val chatStore: ChatStore,
    private val managedChats: ManagedChatPolicy,
    private val reactionRepository: MessageReactionRepository?,
    private val projectionHooks: MessageProjectionHooks,
    private val projectionReadiness: MessageProjectionReadiness,
    private val lifecycleGate: ChatLifecycleGate,
) {
    /** 固定条带避免按消息创建锁导致无界缓存，同时串行化同一 chat+seq 的 outbox 投影。 */
    private val projectionLocks = Array(PROJECTION_LOCK_STRIPES) { Mutex() }
    private val recoveryMutex = Mutex()

    /**
     * 重放完整的持久化操作可靠发件箱，而不仅仅是一个启动页。只有经过两次全局空观察、
     * 且这两次观察之间没有并发失败改变代号（generation）时，就绪状态才被清除。
     */
    suspend fun recoverPendingProjections(
        limit: Int = 1_000,
        maxEncodedBytes: Long = DEFAULT_PENDING_PROJECTION_PAGE_BYTES,
    ): Int {
        require(limit > 0) { "Projection page size must be positive" }
        require(maxEncodedBytes > 0L) { "Projection page byte budget must be positive" }
        return recoveryMutex.withLock {
            var recovered = 0
            var emptyScans = 0
            var observedGeneration = projectionReadiness.generation()
            while (true) {
                val pending = messages.getPendingProjectionOperations(limit, maxEncodedBytes)
                if (pending.isEmpty()) {
                    emptyScans += 1
                    if (emptyScans < REQUIRED_EMPTY_SCANS) continue
                    if (projectionReadiness.markReadyIfUnchanged(observedGeneration)) return@withLock recovered
                    observedGeneration = projectionReadiness.generation()
                    emptyScans = 0
                    continue
                }
                emptyScans = 0
                for (operation in pending) {
                    projectOperation(operation)
                    recovered += 1
                }
            }
            @Suppress("UNREACHABLE_CODE")
            recovered
        }
    }

    /**
     * 只读的运行时恢复探针。投影失败后健康检查可能移除普通业务流量，因此仅靠请求触发的
     * 恢复永远不能成为活性（liveness）的责任者。
     */
    fun hasBlockedProjection(): Boolean = projectionReadiness.currentFailure() != null

    private suspend fun recoverIfBlocked() {
        if (projectionReadiness.currentFailure() != null) recoverPendingProjections()
    }

    /**
     * 等待投影恢复，同时绝不重入不可重入的聊天闸门。
     *
     * 一个命令可能先通过第一次就绪检查，然后排在一个持久化了消息、但 PostgreSQL 投影失败
     * 的命令之后。获取闸门后重新检查，可以防止那个排队的命令先追加一个更晚的序号。恢复
     * 本身必须发生在释放闸门之后，因为全局排空会为挂起工作获取同一个聊天闸门。
     */
    suspend fun <T> withProjectionReadyChats(
        vararg chatIds: String,
        block: suspend () -> T,
    ): T {
        while (true) {
            recoverIfBlocked()
            val attempt = lifecycleGate.withChats(*chatIds) {
                if (projectionReadiness.currentFailure() == null) {
                    ProjectionReadyResult(block())
                } else {
                    null
                }
            }
            if (attempt != null) return attempt.value
        }
    }

    /** 调用方持有此聊天的 [lifecycleGate]。 */
    suspend fun drainPendingForMessageLocked(chatId: String, serverSeq: Long) {
        while (true) {
            val pending = messages.getPendingProjectionOperations(chatId, serverSeq, PROJECTION_MESSAGE_PAGE_SIZE)
            if (pending.isEmpty()) return
            pending.forEach { projectOperationLocked(it) }
        }
    }

    private suspend fun projectOperation(operation: MessageProjectionOperation) {
        lifecycleGate.withChat(operation.message.chatId) { projectOperationLocked(operation) }
    }

    /** 必须在聊天生命周期闸门下运行；分条锁为一个消息身份去重。 */
    private suspend fun projectOperationLocked(operation: MessageProjectionOperation) {
        val message = operation.message
        try {
            // 等待分条期间的取消发生在 withLock 进入其块之前。
            // 把该窗口保持在与之后每个投影阶段相同的就绪终态内。
            projectionHooks.hit(MessageProjectionStage.BEFORE_PROJECTION_LOCK, operation)
            projectionLocks[projectionLockIndex(message.chatId, message.serverSeq)].withLock {
                // 并发的命令重试与全局恢复都可能观察到同一个不可变操作。
                if (!messages.isProjectionPending(operation)) return
                projectionHooks.hit(MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION, operation)
                val searchText = when (operation.operation) {
                    MessageOperationType.REVOKE -> null
                    MessageOperationType.CREATE,
                    MessageOperationType.EDIT,
                    -> MessageTextExtractor.extractSearchText(message, message.body)
                }
                val preview = if (operation.operation == MessageOperationType.REVOKE) {
                    ""
                } else {
                    MessageTextExtractor.toConversationPreview(searchText)
                }
                search.applyProjection(operation, searchText)
                projectionHooks.hit(MessageProjectionStage.AFTER_LUCENE_BEFORE_POSTGRES, operation)

                unitOfWork.write {
                    // 每个已有聊天的写入者都在外部回执仓库获取 Chat 之前，先通过受管修订
                    // 围栏进入。挂起中的组织修订必须让这条 Rocks 操作保持可重放，
                    // 而不是投影过期的成员。
                    val authority = managedChats.lockAuthority(
                        transaction,
                        listOf(message.chatId),
                    ).getValue(message.chatId)
                    require(authority.ready) { "受管群投影尚未收敛" }
                    val applied = projectionRepository.apply(transaction, operation, preview)
                    if (applied.applied) {
                        if (operation.operation == MessageOperationType.REVOKE) {
                            // 撤回投影与回应清理共享一个事务：事件携带的撤回消息与已清空的
                            // 聚合行对客户端原子一致，不会出现撤回气泡上残留回应。
                            reactionRepository?.deleteForMessage(
                                transaction,
                                message.chatId,
                                message.serverSeq,
                            )
                        }
                        for (recipient in applied.recipients) {
                            appendEvent(
                                recipient.uid,
                                NotifyType.MESSAGE_RECV,
                                message,
                            )
                            recipient.conversation?.let { conversation ->
                                appendEvent(
                                    recipient.uid,
                                    NotifyType.CONVERSATION_UPDATED,
                                    conversation,
                                )
                            }
                        }
                    }
                    if (operation.operation == MessageOperationType.CREATE) {
                        afterCommit { chatStore.invalidateManagedChat(message.chatId) }
                    }
                }
                projectionHooks.hit(MessageProjectionStage.AFTER_POSTGRES_BEFORE_OUTBOX_DELETE, operation)
                messages.markProjectionComplete(operation)
                projectionHooks.hit(MessageProjectionStage.AFTER_OUTBOX_DELETE_BEFORE_MESSAGE_RETURN, operation)
            }
        } catch (error: Throwable) {
            projectionReadiness.block("${operation.projectionKey}@${operation.revision}", error)
            throw error
        }
    }

    private fun projectionLockIndex(chatId: String, serverSeq: Long): Int {
        val seqHash = (serverSeq xor (serverSeq ushr 32)).toInt()
        val hash = 31 * chatId.hashCode() + seqHash
        return (hash and Int.MAX_VALUE) % projectionLocks.size
    }

    companion object {
        /**
         * 单条权威 RichText 最坏会在线上同时携带 Markdown、plainText 和 mention 侧信道。
         * 十条上限为 16 MiB 帧保留数 MiB 信封余量，也阻止任意 limit 驱动存储/索引巨量扫描。
         */
        private const val PROJECTION_LOCK_STRIPES = 256
        private const val PROJECTION_MESSAGE_PAGE_SIZE = 100
        private const val REQUIRED_EMPTY_SCANS = 2
    }
}

private data class ProjectionReadyResult<T>(val value: T)
