package com.virjar.tk.client

import com.virjar.tk.database.AppDatabaseQueries
import com.virjar.tk.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 单个聊道的消息内存窗口。
 *
 * 内存中只保留最近 [windowSize] 条消息（按 serverSeq 降序，最新在前）。
 * 调用 [loadMore] 向上翻页加载更老消息，追加到窗口末尾。
 * 当窗口大小超过 [windowSize] * 2 时自动裁剪最老的消息（保留 hasMore=true）。
 *
 * 所有操作不持有 LocalCacheImpl 引用，通过 [queries] 直接访问 DB。SQLite 访问和
 * resident-window 发布都必须先取得 [cacheUseGate]，因此 cache close 会等待已经准入的
 * pager 操作退出，且 close 返回后旧 pager 无法触碰已关闭的 driver。
 */
internal class MessageWindow(
    private val chatId: String,
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val windowSize: Int,
    toModel: (com.virjar.tk.database.Message) -> Message,
) : MessagePager {
    private val stateLock = Any()
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    override val messages: Flow<List<Message>> = _messages.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    override val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /**
     * False while the window is only a best-effort SQLite snapshot. Once a server page arrives,
     * only pages from that explicit response chain may extend the cursor; unrelated cached rows
     * below it are a stale tail until the server returns them again.
     */
    private var serverPageAnchored = false
    private var historyCursor: Long? = null
    /**
     * Atomic cursor captured after a live upsert trims server-proven history. `loadMore` consumes
     * it and asks the ViewModel to refetch from the server. This keeps resident memory bounded
     * without reviving an unrelated stale SQLite tail.
     */
    private var remoteRefetchBeforeSeq: Long? = null

    init {
        require(windowSize > 0) { "windowSize must be positive" }
    }

    // 消息映射函数（从 LocalCacheImpl 传入，复用 toModel 逻辑）
    private val toModelFn = toModel

    init {
        loadInitialWindow()
    }

    private fun loadInitialWindow() = cacheUseGate.use {
        synchronized(stateLock) {
            val authorityAnchor = queries.selectLatestAuthoritativeMessagesByChat(chatId, 1L).executeAsList()
            val optimisticCapacity = windowSize - if (authorityAnchor.isEmpty()) 0 else 1
            val optimistic = queries.selectOptimisticMessagesByChat(
                chatId,
                optimisticCapacity.coerceAtLeast(0).toLong(),
            ).executeAsList()
            val authoritativeCapacity = (windowSize - optimistic.size).coerceAtLeast(0)
            val authoritative = if (authoritativeCapacity == 1 && authorityAnchor.isNotEmpty()) {
                authorityAnchor
            } else {
                queries.selectLatestAuthoritativeMessagesByChat(
                    chatId,
                    authoritativeCapacity.toLong(),
                ).executeAsList()
            }
            val rows = optimistic + authoritative
            val msgs = rows.map { toModelFn(it) }.sortedWith(messageOrder)
            _messages.value = msgs
            historyCursor = oldestServerSeq(msgs)
            refreshHasMore(msgs)
        }
    }

    private fun refreshHasMore(currentMsgs: List<Message>) {
        if (serverPageAnchored) {
            _hasMore.value = remoteRefetchBeforeSeq != null
            return
        }
        val cursor = historyCursor ?: oldestServerSeq(currentMsgs)
        _hasMore.value = cursor != null &&
            queries.selectMessagesByChatBefore(chatId, cursor, 1L).executeAsList().isNotEmpty()
    }

    override fun loadMore(pageSize: Int): MessagePageLoadResult = cacheUseGate.use {
        synchronized(stateLock) {
            require(pageSize > 0) { "pageSize must be positive" }
            if (serverPageAnchored) {
                val refetchCursor = remoteRefetchBeforeSeq
                    ?: return@synchronized MessagePageLoadResult.Exhausted
                // A failed RPC remains retryable through ChatViewModel.remoteHasMore. Any live
                // trim after this point installs a new cursor which applyHistoryPage preserves.
                remoteRefetchBeforeSeq = null
                refreshHasMore(_messages.value)
                return@synchronized MessagePageLoadResult.RemoteRequired(refetchCursor)
            }
            val current = _messages.value
            val oldestSeq = historyCursor ?: oldestServerSeq(current)
                ?: return@synchronized MessagePageLoadResult.Exhausted
            if (!_hasMore.value) return@synchronized MessagePageLoadResult.Exhausted

            val olderRows = queries.selectMessagesByChatBefore(chatId, oldestSeq, pageSize.toLong()).executeAsList()
            val existingIds = current.asSequence().map(Message::clientMsgId).toHashSet()
            val older = olderRows.map(toModelFn).filter { existingIds.add(it.clientMsgId) }
            val merged = (current + older).sortedWith(messageOrder).toMutableList()
            trimForOlderPage(merged, older.mapTo(mutableSetOf(), Message::clientMsgId))
            _messages.value = merged
            historyCursor = oldestServerSeq(merged)
            refreshHasMore(merged)
            if (older.isEmpty()) MessagePageLoadResult.Exhausted else MessagePageLoadResult.LocalLoaded
        }
    }

    /**
     * Apply one RPC history response as an atomic, server-proven page. Sequence numbers are
     * cursors, not a promise of `n - 1` adjacency: legal holes inside or between pages are kept.
     */
    fun applyHistoryPage(page: List<Message>, resetResidentWindow: Boolean) = cacheUseGate.use {
        synchronized(stateLock) {
            val startNewChain = resetResidentWindow || !serverPageAnchored
            val base = if (startNewChain) {
                val pageMaxSeq = page.asSequence().map(Message::serverSeq).filter { it > 0L }.maxOrNull()
                _messages.value.filter { message ->
                    message.serverSeq <= 0L || (pageMaxSeq != null && message.serverSeq > pageMaxSeq)
                }
            } else {
                _messages.value
            }
            val merged = mergeByClientId(base, page).toMutableList()
            serverPageAnchored = true
            if (startNewChain) {
                remoteRefetchBeforeSeq = null
                if (trimOldestForLatestWindow(merged)) {
                    remoteRefetchBeforeSeq = oldestServerSeq(merged)
                }
            } else {
                // Older authoritative pages move the bounded window backwards. A cursor installed
                // by a concurrent live upsert is deliberately preserved for the next request.
                trimForOlderPage(merged, page.mapTo(mutableSetOf(), Message::clientMsgId))
            }
            historyCursor = oldestServerSeq(merged)
            _messages.value = merged
            refreshHasMore(merged)
        }
    }

    /**
     * 新消息到达（NOTIFY 推送）或发送时调用，更新内存窗口。
     * 如果窗口不存在该消息则插入到最前面（最新），存在则更新。
     */
    fun upsert(message: Message) = cacheUseGate.use {
        synchronized(stateLock) {
            val current = _messages.value.toMutableList()
            val idx = current.indexOfFirst { it.clientMsgId == message.clientMsgId }
            if (idx >= 0) {
                current[idx] = message
            } else {
                // Single-message events are not history-page provenance. Once anchored, an event below
                // the cursor is persisted by LocalCacheImpl but stays hidden until an RPC page proves
                // that it belongs to the active history chain.
                val cursor = historyCursor
                if (serverPageAnchored && message.serverSeq > 0L && cursor != null && message.serverSeq < cursor) {
                    refreshHasMore(current)
                    return@synchronized
                }
                current.add(message)
            }
            // Keep the public order explicit for live/pending upserts. RPC history uses the atomic
            // applyHistoryPage path above; pending local messages (seq=0) remain before confirmed
            // history and are ordered by their local timestamp.
            current.sortWith(messageOrder)
            val trimmedAuthority = trimOldestForLatestWindow(current)
            if (serverPageAnchored && trimmedAuthority) {
                remoteRefetchBeforeSeq = oldestServerSeq(current)
            }
            _messages.value = current
            historyCursor = oldestServerSeq(current)
            refreshHasMore(current)
        }
    }

    /**
     * 更新消息状态（serverSeq / sendStatus / 任意变换，如上传进度）。
     */
    fun updateMessage(
        clientMsgId: String,
        serverSeq: Long? = null,
        sendStatus: Int? = null,
        transform: (Message.() -> Message)? = null,
    ) = cacheUseGate.use {
        synchronized(stateLock) {
            val current = _messages.value.toMutableList()
            val idx = current.indexOfFirst { it.clientMsgId == clientMsgId }
            if (idx < 0) return@synchronized
            current[idx] = current[idx].let {
                transform?.invoke(it) ?: it.copy(
                    serverSeq = serverSeq ?: it.serverSeq,
                    sendStatus = sendStatus ?: it.sendStatus,
                )
            }
            current.sortWith(messageOrder)
            _messages.value = current
            historyCursor = oldestServerSeq(current)
            refreshHasMore(current)
        }
    }

    fun deleteMessage(clientMsgId: String) = cacheUseGate.use {
        synchronized(stateLock) {
            val current = _messages.value.filter { it.clientMsgId != clientMsgId }
            _messages.value = current
            historyCursor = oldestServerSeq(current)
            refreshHasMore(current)
        }
    }

    /**
     * Clear the resident server projection without detaching existing collectors.
     *
     * SYNC_RESET keeps this window instance registered in LocalCacheImpl so replayed messages
     * repopulate the same Flow observed by an already-open chat screen.
     */
    fun resetServerProjection() = cacheUseGate.use {
        synchronized(stateLock) {
            _messages.value = emptyList()
            _hasMore.value = false
            serverPageAnchored = false
            historyCursor = null
            remoteRefetchBeforeSeq = null
        }
    }

    /** Latest/live windows retain their newest facts and report whether confirmed history fell out. */
    private fun trimOldestForLatestWindow(list: MutableList<Message>): Boolean {
        var trimmedAuthority = false
        if (list.size > maxCapacity) {
            var authorityCount = list.count { it.serverSeq > 0L }
            val dropCount = list.size - maxCapacity
            repeat(dropCount) {
                val oldestIndex = list.lastIndex
                val removalIndex = if (
                    list[oldestIndex].serverSeq > 0L && authorityCount == 1
                ) {
                    list.indexOfLast { it.serverSeq == 0L }.takeIf { it >= 0 } ?: oldestIndex
                } else {
                    oldestIndex
                }
                if (list[removalIndex].serverSeq > 0L) {
                    authorityCount -= 1
                    trimmedAuthority = true
                }
                list.removeAt(removalIndex)
            }
        }
        return trimmedAuthority
    }

    /**
     * Paging backwards must retain the page just loaded. Once the 2x cap is reached, evict newer
     * confirmed rows (while preserving one newest authority anchor) instead of deleting the tail
     * and repeating the same SQLite/server cursor forever. Optimistic rows normally stay visible,
     * but a saturated window may evict the oldest ones so the newly loaded cursor can advance;
     * their durable projection remains in SQLite and returns with a fresh resident window.
     */
    private fun trimForOlderPage(
        list: MutableList<Message>,
        loadedPageIds: Set<String>,
    ) {
        while (list.size > maxCapacity) {
            val firstAuthority = list.indexOfFirst { it.serverSeq > 0L }
            val unprotectedAuthority = list.indices.firstOrNull { index ->
                index != firstAuthority &&
                    list[index].serverSeq > 0L &&
                    list[index].clientMsgId !in loadedPageIds
            } ?: -1
            val oldestLoadedAuthority = list.indexOfLast { message ->
                message.serverSeq > 0L && message.clientMsgId in loadedPageIds
            }
            val newerLoadedAuthority = list.indices.firstOrNull { index ->
                index != oldestLoadedAuthority &&
                    list[index].serverSeq > 0L &&
                    list[index].clientMsgId in loadedPageIds
            } ?: -1
            val removalIndex = when {
                unprotectedAuthority >= 0 -> unprotectedAuthority
                list.any { it.serverSeq == 0L } -> list.indexOfLast { it.serverSeq == 0L }
                firstAuthority >= 0 && list[firstAuthority].clientMsgId !in loadedPageIds -> firstAuthority
                newerLoadedAuthority >= 0 -> newerLoadedAuthority
                else -> list.lastIndex
            }
            list.removeAt(removalIndex)
        }
    }

    /** 当前窗口快照（用于 getMessages 同步访问）。 */
    fun snapshot(limit: Int): List<Message> = cacheUseGate.use {
        synchronized(stateLock) { _messages.value.take(limit) }
    }

    private val maxCapacity: Int get() = windowSize * 2

    private fun oldestServerSeq(messages: List<Message>): Long? = messages.asSequence()
        .map(Message::serverSeq)
        .filter { it > 0L }
        .minOrNull()

    private fun mergeByClientId(base: List<Message>, page: List<Message>): List<Message> {
        val merged = LinkedHashMap<String, Message>(base.size + page.size)
        base.forEach { merged[it.clientMsgId] = it }
        page.forEach { merged[it.clientMsgId] = it }
        return merged.values.sortedWith(messageOrder)
    }

    private companion object {
        val messageOrder = compareByDescending<Message> {
            if (it.serverSeq > 0L) it.serverSeq else Long.MAX_VALUE
        }.thenByDescending { it.timestamp }
    }
}
