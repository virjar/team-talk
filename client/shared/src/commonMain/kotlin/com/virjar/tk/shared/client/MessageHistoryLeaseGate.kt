package com.virjar.tk.shared.client

/**
 * 面向模块外 [LocalCache] 历史实现的代际隔断。
 *
 * Owner 身份、全局 epoch、全局单调 chat 生命周期令牌、请求 id 与历史链 id 对该能力保持私有。
 * 实现只能开始一个租约、消费其仍然当前的结果、放弃它、释放一个非常驻 chat、使一个 chat 失效，
 * 或 reset 完整投影。调用方拥有每个操作的同步，因此 [consumeIfCurrent] 与对应的缓存写入构成
 * 一个原子步骤。
 */
class MessageHistoryLeaseGate(
    private val label: String = "message history lease",
) {
    private data class ChatState(
        val lifecycleToken: Long,
        var releaseWhenIdle: Boolean = false,
        var historyChainId: Long = 0L,
        var committedHistoryChainId: Long = 0L,
        var pendingNewestChainId: Long = 0L,
        var newestRequestId: Long = 0L,
        var olderRequestId: Long = 0L,
        val newestMutatedClientMsgIds: LinkedHashSet<String> = linkedSetOf(),
        val olderMutatedClientMsgIds: LinkedHashSet<String> = linkedSetOf(),
        val newestLiveClientMsgIds: LinkedHashSet<String> = linkedSetOf(),
        val olderLiveClientMsgIds: LinkedHashSet<String> = linkedSetOf(),
        var newestMutationOverflow: Boolean = false,
        var olderMutationOverflow: Boolean = false,
    ) {
        val hasCurrentRequest: Boolean
            get() = newestRequestId != 0L || olderRequestId != 0L
    }

    private val owner = Any()
    private var globalEpoch = 0L
    private var nextChatLifecycleToken = 0L
    private var nextRequestId = 0L
    private val chats = mutableMapOf<String, ChatState>()

    /** 开始一个最新页或更旧页请求，并返回其不透明能力。 */
    fun begin(chatId: String, resetResidentWindow: Boolean): MessageHistoryLease {
        require(chatId.isNotBlank()) { "$label chatId must not be blank" }
        val state = chats.getOrPut(chatId, ::newChatState)
        nextRequestId = next(nextRequestId, "$label request id")
        if (resetResidentWindow) {
            state.historyChainId = next(state.historyChainId, "$label history chain")
            state.pendingNewestChainId = state.historyChainId
            state.newestRequestId = nextRequestId
            state.newestMutatedClientMsgIds.clear()
            state.newestLiveClientMsgIds.clear()
            state.newestMutationOverflow = false
        } else {
            state.olderRequestId = nextRequestId
            state.olderMutatedClientMsgIds.clear()
            state.olderLiveClientMsgIds.clear()
            state.olderMutationOverflow = false
        }
        return MessageHistoryLease(
            chatId = chatId,
            owner = owner,
            globalGeneration = globalEpoch,
            chatLifecycleGeneration = state.lifecycleToken,
            requestGeneration = nextRequestId,
            historyChainGeneration = if (resetResidentWindow) {
                state.pendingNewestChainId
            } else {
                state.committedHistoryChainId
            },
            resetResidentWindow = resetResidentWindow,
        )
    }

    /**
     * 只对精确当前租约运行 [apply]，之后提交其链转换。如果 [apply] 抛出，门禁状态不推进，
     * 与回滚的缓存事务一致。
     */
    fun consumeIfCurrent(
        lease: MessageHistoryLease,
        apply: (
            chatId: String,
            resetResidentWindow: Boolean,
            mutatedClientMsgIds: Set<String>,
            liveClientMsgIds: Set<String>,
        ) -> Unit,
    ): Boolean {
        val state = currentState(lease) ?: return false
        val currentRequestId: Long
        val currentChainId: Long
        if (lease.resetResidentWindow) {
            currentRequestId = state.newestRequestId
            currentChainId = state.pendingNewestChainId
        } else {
            if (lease.historyChainGeneration == 0L) return false
            currentRequestId = state.olderRequestId
            currentChainId = state.committedHistoryChainId
        }
        if (currentRequestId != lease.requestGeneration || currentChainId != lease.historyChainGeneration) {
            return false
        }
        val overflow = if (lease.resetResidentWindow) {
            state.newestMutationOverflow
        } else {
            state.olderMutationOverflow
        }
        // 不消费地失败关闭。MessageRepository 的 finally 块放弃该精确租约，
        // 之后的请求以全新的有界变更集合开始。
        if (overflow) return false
        val protectedIds = if (lease.resetResidentWindow) {
            state.newestMutatedClientMsgIds.toSet()
        } else {
            state.olderMutatedClientMsgIds.toSet()
        }
        val liveIds = if (lease.resetResidentWindow) {
            state.newestLiveClientMsgIds.toSet()
        } else {
            state.olderLiveClientMsgIds.toSet()
        }

        apply(lease.chatId, lease.resetResidentWindow, protectedIds, liveIds)
        if (lease.resetResidentWindow) {
            state.committedHistoryChainId = lease.historyChainGeneration
            state.pendingNewestChainId = 0L
            state.newestRequestId = 0L
            state.olderRequestId = 0L
            state.newestMutatedClientMsgIds.clear()
            state.olderMutatedClientMsgIds.clear()
            state.newestLiveClientMsgIds.clear()
            state.olderLiveClientMsgIds.clear()
            state.newestMutationOverflow = false
            state.olderMutationOverflow = false
        } else {
            state.olderRequestId = 0L
            state.olderMutatedClientMsgIds.clear()
            state.olderLiveClientMsgIds.clear()
            state.olderMutationOverflow = false
        }
        removeReleasedStateIfIdle(lease.chatId, state)
        return true
    }

    /** 只释放仍然当前的精确请求，保持其已提交历史锚点不变。 */
    fun abandon(lease: MessageHistoryLease): Boolean {
        val state = currentState(lease) ?: return false
        if (lease.resetResidentWindow) {
            if (state.newestRequestId != lease.requestGeneration ||
                state.pendingNewestChainId != lease.historyChainGeneration
            ) {
                return false
            }
            state.newestRequestId = 0L
            state.pendingNewestChainId = 0L
            state.newestMutatedClientMsgIds.clear()
            state.newestLiveClientMsgIds.clear()
            state.newestMutationOverflow = false
        } else {
            if (state.olderRequestId != lease.requestGeneration ||
                state.committedHistoryChainId != lease.historyChainGeneration
            ) {
                return false
            }
            state.olderRequestId = 0L
            state.olderMutatedClientMsgIds.clear()
            state.olderLiveClientMsgIds.clear()
            state.olderMutationOverflow = false
        }
        removeReleasedStateIfIdle(lease.chatId, state)
        return true
    }

    /**
     * 针对每一个在它之前开始的请求记录同 chat 权威来源。
     *
     * [excluding] 在一个历史页提交之后使用：该页对另一条在途通道被记录，但绝不能与其自己的响应
     * 冲突。相等载荷值仍然计数，因为相等无法区分一次实时编辑/撤回与一个更旧的历史快照。
     */
    fun recordAuthoritativeMutation(
        chatId: String,
        clientMsgId: String,
        excluding: MessageHistoryLease? = null,
        retainIfAbsentFromNewestPage: Boolean = false,
    ) {
        require(chatId.isNotBlank()) { "$label chatId must not be blank" }
        require(clientMsgId.isNotBlank()) { "$label clientMsgId must not be blank" }
        val state = chats[chatId] ?: return
        if (
            state.newestRequestId != 0L &&
            !excluding.namesCurrentLane(state, resetResidentWindow = true)
        ) {
            state.newestMutationOverflow = recordBoundedMutation(
                state.newestMutatedClientMsgIds,
                state.newestLiveClientMsgIds,
                state.newestMutationOverflow,
                clientMsgId,
                retainIfAbsentFromNewestPage,
            )
        }
        if (
            state.olderRequestId != 0L &&
            !excluding.namesCurrentLane(state, resetResidentWindow = false)
        ) {
            state.olderMutationOverflow = recordBoundedMutation(
                state.olderMutatedClientMsgIds,
                state.olderLiveClientMsgIds,
                state.olderMutationOverflow,
                clientMsgId,
                retainIfAbsentFromNewestPage,
            )
        }
    }

    /** 已提交的实时事件必须保持可见，即使一个更旧的最新页响应省略了它。 */
    fun recordLiveAuthoritativeMutation(chatId: String, clientMsgId: String) =
        recordAuthoritativeMutation(chatId, clientMsgId, retainIfAbsentFromNewestPage = true)

    /**
     * 释放一个非常驻 chat 的闲置锚点，而不使已经在途的页失效。最终当前请求的完成或放弃会移除
     * 一个延迟释放。
     */
    fun release(chatId: String) {
        require(chatId.isNotBlank()) { "$label chatId must not be blank" }
        val state = chats[chatId] ?: return
        state.releaseWhenIdle = true
        removeReleasedStateIfIdle(chatId, state)
    }

    /** 当同一 chat 窗口再次变得常驻时取消延迟释放。 */
    fun retain(chatId: String) {
        require(chatId.isNotBlank()) { "$label chatId must not be blank" }
        chats[chatId]?.releaseWhenIdle = false
    }

    /** 调用方拥有同步；保留只能在两条请求通道都闲置之后运行。 */
    internal fun hasCurrentRequest(chatId: String): Boolean = chats[chatId]?.hasCurrentRequest == true

    /** 使一个已删除/重建 chat 的每个在途请求失效。 */
    fun invalidate(chatId: String) {
        require(chatId.isNotBlank()) { "$label chatId must not be blank" }
        chats.remove(chatId)
    }

    /** 使完整服务器投影 reset 或缓存关闭之前签发的每个租约失效。 */
    fun reset() {
        globalEpoch = next(globalEpoch, "$label global epoch")
        chats.clear()
    }

    private fun currentState(lease: MessageHistoryLease): ChatState? {
        if (lease.owner !== owner || lease.globalGeneration != globalEpoch) return null
        return chats[lease.chatId]?.takeIf { it.lifecycleToken == lease.chatLifecycleGeneration }
    }

    private fun MessageHistoryLease?.namesCurrentLane(
        state: ChatState,
        resetResidentWindow: Boolean,
    ): Boolean {
        val lease = this ?: return false
        if (
            lease.owner !== owner ||
            lease.globalGeneration != globalEpoch ||
            lease.chatLifecycleGeneration != state.lifecycleToken ||
            lease.resetResidentWindow != resetResidentWindow
        ) {
            return false
        }
        return if (resetResidentWindow) {
            lease.requestGeneration == state.newestRequestId &&
                lease.historyChainGeneration == state.pendingNewestChainId
        } else {
            lease.requestGeneration == state.olderRequestId &&
                lease.historyChainGeneration == state.committedHistoryChainId
        }
    }

    private fun recordBoundedMutation(
        mutations: LinkedHashSet<String>,
        liveMutations: LinkedHashSet<String>,
        alreadyOverflowed: Boolean,
        clientMsgId: String,
        retainIfAbsentFromNewestPage: Boolean,
    ): Boolean {
        if (alreadyOverflowed) return true
        if (clientMsgId in mutations) {
            if (retainIfAbsentFromNewestPage) liveMutations += clientMsgId
            return false
        }
        if (mutations.size >= MAX_MUTATED_KEYS_PER_REQUEST) {
            mutations.clear()
            liveMutations.clear()
            return true
        }
        mutations += clientMsgId
        if (retainIfAbsentFromNewestPage) liveMutations += clientMsgId
        return false
    }

    private fun newChatState(): ChatState {
        nextChatLifecycleToken = next(nextChatLifecycleToken, "$label chat lifecycle token")
        return ChatState(lifecycleToken = nextChatLifecycleToken)
    }

    private fun removeReleasedStateIfIdle(chatId: String, state: ChatState) {
        if (state.releaseWhenIdle && !state.hasCurrentRequest && chats[chatId] === state) {
            chats.remove(chatId)
        }
    }

    private fun next(current: Long, counter: String): Long {
        check(current < Long.MAX_VALUE) { "$counter exhausted" }
        return current + 1L
    }

    companion object {
        const val MAX_MUTATED_KEYS_PER_REQUEST = 512
    }
}
