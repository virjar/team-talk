package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Message
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
 * 所有操作不持有 LocalCacheImpl/LocalMessageStore 引用，通过 [queries] 直接访问 DB。SQLite 访问和
 * resident-window 发布都必须先取得 [cacheUseGate]，因此 cache close 会等待已经准入的
 * pager 操作退出，且 close 返回后旧 pager 无法触碰已关闭的 driver。
 */
internal class MessageWindow(
    private val chatId: String,
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val windowSize: Int,
    toModel: (com.virjar.tk.shared.database.Message) -> Message,
) {
    private val stateLock = Any()
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    private val leasePublications = linkedMapOf<Long, RetirableProjectionState<List<Message>>>()
    /** 当收集者同步执行一次更新变更时，隔断外层发布。 */
    private var publicationGeneration = 0L

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /**
     * 当窗口只是一个 best-effort SQLite 快照时为 false。一旦服务器页到达，只有来自该显式响应链
     * 的页可以推进游标；其下方不相关的缓存行在服务器再次返回它们之前是过期尾部。
     */
    private var serverPageAnchored = false
    private var historyCursor: Long? = null
    /**
     * 在一次实时 upsert 裁剪服务器证明的历史之后捕获的原子游标。`loadMore` 消费它，并要求
     * ViewModel 从服务器重新拉取。这在不复活不相关过期 SQLite 尾部的前提下保持常驻内存有界。
     */
    private var remoteRefetchBeforeSeq: Long? = null

    init {
        require(windowSize in 1..MessagePager.MAX_WINDOW_SIZE) {
            "windowSize must be between 1 and ${MessagePager.MAX_WINDOW_SIZE}"
        }
    }

    // 消息映射函数由 session-owned message store 注入，窗口不依赖组合根。
    private val toModelFn = toModel

    init {
        loadInitialWindow()
    }

    private fun loadInitialWindow() = cacheUseGate.use {
        synchronized(stateLock) {
            val msgs = loadBoundedInitialMessages(queries, chatId, windowSize, toModelFn)
            publishMessages(msgs)
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

    fun loadMore(pageSize: Int): MessagePageLoadResult = cacheUseGate.use {
        synchronized(stateLock) {
            require(pageSize in 1..MessagePager.MAX_PAGE_SIZE) {
                "pageSize must be between 1 and ${MessagePager.MAX_PAGE_SIZE}"
            }
            if (serverPageAnchored) {
                val refetchCursor = remoteRefetchBeforeSeq
                    ?: return@synchronized MessagePageLoadResult.Exhausted
                // 失败的 RPC 仍可通过 ChatViewModel.remoteHasMore 重试。此后任何实时裁剪都会安装
                // 一个新游标，applyHistoryPage 会保留它。
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
            val merged = (current + older).sortedWith(messageDisplayOrder).toMutableList()
            trimForOlderPage(merged, older.mapTo(mutableSetOf(), Message::clientMsgId))
            publishMessages(merged)
            historyCursor = oldestServerSeq(merged)
            refreshHasMore(merged)
            if (older.isEmpty()) MessagePageLoadResult.Exhausted else MessagePageLoadResult.LocalLoaded
        }
    }

    /**
     * 把一个 RPC 历史响应应用为原子的、服务器证明的页。sequence 号是游标，不是 `n - 1` 相邻的
     * 承诺：页内或页间的合法空洞被保留。
     */
    fun applyHistoryPage(
        page: List<Message>,
        resetResidentWindow: Boolean,
        preserveClientMsgIds: Set<String> = emptySet(),
        retainResidentClientMsgIds: Set<String> = emptySet(),
        preservedDurableMessages: List<Message> = emptyList(),
    ) = cacheUseGate.use {
        synchronized(stateLock) {
            require(preservedDurableMessages.all { it.clientMsgId in preserveClientMsgIds }) {
                "preserved history fallback is missing its mutation fence"
            }
            val startNewChain = resetResidentWindow || !serverPageAnchored
            val base = if (startNewChain) {
                val pageMaxSeq = page.asSequence().map(Message::serverSeq).filter { it > 0L }.maxOrNull()
                _messages.value.filter { message ->
                    message.clientMsgId in preserveClientMsgIds ||
                        message.clientMsgId in retainResidentClientMsgIds ||
                        message.serverSeq <= 0L ||
                        (pageMaxSeq != null && message.serverSeq > pageMaxSeq)
                }
            } else {
                _messages.value
            }
            val merged = mergeHistoryPage(
                base = base,
                page = page,
                preserveClientMsgIds = preserveClientMsgIds,
                preservedDurableMessages = preservedDurableMessages,
            ).toMutableList()
            serverPageAnchored = true
            if (startNewChain) {
                remoteRefetchBeforeSeq = null
                if (trimOldestForLatestWindow(merged)) {
                    remoteRefetchBeforeSeq = oldestServerSeq(merged)
                }
            } else {
                // 更旧的权威页把有界窗口向后移动。并发实时 upsert 安装的游标被刻意保留给下一次请求。
                trimForOlderPage(merged, page.mapTo(mutableSetOf(), Message::clientMsgId))
            }
            historyCursor = oldestServerSeq(merged)
            publishMessages(merged)
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
                // 单消息事件不是历史页来源。一旦锚定，游标之下的事件由 LocalCacheImpl 持久化，
                // 但保持隐藏，直到一个 RPC 页证明它属于活跃历史链。
                val cursor = historyCursor
                if (serverPageAnchored && message.serverSeq > 0L && cursor != null && message.serverSeq < cursor) {
                    refreshHasMore(current)
                    return@synchronized
                }
                current.add(message)
            }
            // 对实时/待处理 upsert 保持公开顺序显式。RPC 历史走上面的原子 applyHistoryPage 路径；
            // 待处理本地消息（seq=0）保持在已确认历史之前，并按其本地时间戳排序。
            current.sortWith(messageDisplayOrder)
            val trimmedAuthority = trimOldestForLatestWindow(current)
            if (serverPageAnchored && trimmedAuthority) {
                remoteRefetchBeforeSeq = oldestServerSeq(current)
            }
            publishMessages(current)
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
        beforePublish: (() -> Unit)? = null,
    ) = cacheUseGate.use {
        synchronized(stateLock) {
            val current = _messages.value.toMutableList()
            val idx = current.indexOfFirst { it.clientMsgId == clientMsgId }
            if (idx < 0) return@synchronized
            val replacement = current[idx].let {
                transform?.invoke(it) ?: it.copy(
                    serverSeq = serverSeq ?: it.serverSeq,
                    sendStatus = sendStatus ?: it.sendStatus,
                )
            }
            beforePublish?.invoke()
            current[idx] = replacement
            current.sortWith(messageDisplayOrder)
            publishMessages(current)
            historyCursor = oldestServerSeq(current)
            refreshHasMore(current)
        }
    }

    /** 预留非持久乐观编辑时使用的精确常驻查找。 */
    fun currentMessage(clientMsgId: String): Message? = cacheUseGate.use {
        synchronized(stateLock) { _messages.value.firstOrNull { it.clientMsgId == clientMsgId } }
    }

    /**
     * 替换一个精确常驻投影。相等是刻意的：同值服务器事件会先取代存储预留，因此即使载荷字节恰好
     * 匹配乐观覆盖层，回滚也能区分来源。
     */
    fun replaceMessageIfCurrent(expected: Message, replacement: Message): Boolean = cacheUseGate.use {
        require(expected.chatId == chatId && replacement.chatId == chatId) {
            "optimistic edit belongs to another chat"
        }
        require(expected.clientMsgId == replacement.clientMsgId) {
            "optimistic edit cannot change clientMsgId"
        }
        require(expected.serverSeq == replacement.serverSeq) {
            "optimistic edit cannot change serverSeq"
        }
        synchronized(stateLock) {
            val current = _messages.value.toMutableList()
            val index = current.indexOfFirst { it.clientMsgId == expected.clientMsgId }
            if (index < 0 || current[index] != expected) return@synchronized false
            current[index] = replacement
            current.sortWith(messageDisplayOrder)
            publishMessages(current)
            historyCursor = oldestServerSeq(current)
            refreshHasMore(current)
            true
        }
    }

    fun deleteMessage(clientMsgId: String) = cacheUseGate.use {
        synchronized(stateLock) {
            val current = _messages.value.filter { it.clientMsgId != clientMsgId }
            publishMessages(current)
            historyCursor = oldestServerSeq(current)
            refreshHasMore(current)
        }
    }

    /** 把失败消息移除加替代准入作为一个常驻快照发布。 */
    fun replaceMessage(clientMsgId: String, replacement: Message) = cacheUseGate.use {
        require(replacement.chatId == chatId) { "replacement message belongs to another chat" }
        require(replacement.clientMsgId != clientMsgId) { "replacement must use a fresh clientMsgId" }
        synchronized(stateLock) {
            val current = _messages.value.filterTo(mutableListOf()) {
                it.clientMsgId != clientMsgId && it.clientMsgId != replacement.clientMsgId
            }
            current += replacement
            current.sortWith(messageDisplayOrder)
            val trimmedAuthority = trimOldestForLatestWindow(current)
            if (serverPageAnchored && trimmedAuthority) {
                remoteRefetchBeforeSeq = oldestServerSeq(current)
            }
            publishMessages(current)
            historyCursor = oldestServerSeq(current)
            refreshHasMore(current)
        }
    }

    /**
     * 清除常驻服务器投影，而不拆离现有收集者。
     *
     * SYNC_RESET 让该窗口实例保持在 LocalCacheImpl 中注册，这样重放消息会重新填充已被打开聊天页
     * 观察的同一个 Flow。
     */
    fun resetServerProjection() = cacheUseGate.use {
        synchronized(stateLock) {
            publishMessages(emptyList())
            _hasMore.value = false
            serverPageAnchored = false
            historyCursor = null
            remoteRefetchBeforeSeq = null
        }
    }

    /** 最新/实时窗口保留其最新事实，并报告已确认历史是否被挤出。 */
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
     * 向后翻页必须保留刚加载的页。一旦达到 2x 上限，驱逐更新的已确认行（同时保留一个最新权威锚点），
     * 而不是删除尾部并永远重复同一个 SQLite/服务器游标。乐观行通常保持可见，但饱和窗口可能驱逐
     * 最旧的乐观行，好让新加载的游标可以推进；其持久投影留在 SQLite 中，并随新的常驻窗口返回。
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

    /** 注册一个精确外部 owner。调用方已经拥有 LocalMessageStore 的准入锁。 */
    fun acquireLease(leaseId: Long): MessageWindowLeaseState = synchronized(stateLock) {
        check(leaseId > 0L && leaseId !in leasePublications) { "Duplicate message window lease id" }
        val publication = RetirableProjectionState(_messages.value)
        leasePublications[leaseId] = publication
        MessageWindowLeaseState(
            messages = publication.observe(),
            hasMore = hasMore,
        )
    }

    /** 只退役精确 owner 的发布；该窗口的另一个 owner 保持附着。 */
    fun releaseLease(leaseId: Long): Boolean = synchronized(stateLock) {
        val publication = leasePublications.remove(leaseId) ?: return@synchronized false
        publication.retire(emptyList())
        true
    }

    /** 缓存关闭/条目驱逐终态完成每个被捕获的 owner flow。 */
    fun retireAllLeases() = synchronized(stateLock) {
        val publications = leasePublications.values.toList()
        leasePublications.clear()
        publications.forEach { publication -> publication.retire(emptyList()) }
    }

    private fun publishMessages(messages: List<Message>) {
        check(publicationGeneration < Long.MAX_VALUE) { "message publication generation exhausted" }
        val generation = ++publicationGeneration
        _messages.value = messages
        // StateFlow 可能在该 setter 内部恢复一个 undispatched 收集者。该收集者被允许重入地关闭
        // 自己的（或另一个）pager，因此绝不遍历活动 map，也绝不发布进一个该回调已退役的 owner。
        leasePublications.toList().forEach { (leaseId, publication) ->
            // 收集者可能同步发布一个更新的投影。之后绝不恢复这个更旧的扇出，
            // 否则不同的 pager owner 可能观察到发散的最终值。
            if (publicationGeneration != generation) return
            if (leasePublications[leaseId] === publication) publication.value = messages
        }
    }

    private val maxCapacity: Int get() = windowSize * 2

    private fun oldestServerSeq(messages: List<Message>): Long? = messages.asSequence()
        .map(Message::serverSeq)
        .filter { it > 0L }
        .minOrNull()

    private fun mergeHistoryPage(
        base: List<Message>,
        page: List<Message>,
        preserveClientMsgIds: Set<String>,
        preservedDurableMessages: List<Message>,
    ): List<Message> {
        val merged = LinkedHashMap<String, Message>(base.size + page.size)
        base.forEach { merged[it.clientMsgId] = it }
        page.forEach { message ->
            if (message.clientMsgId !in preserveClientMsgIds) merged[message.clientMsgId] = message
        }
        // 常驻游标之下的实时事件可能只存在于 SQLite 中。一旦该历史页证明该 key 属于这里，
        // 加入这个持久赢家，而不替换基于它的更新仅常驻乐观覆盖层。
        preservedDurableMessages.forEach { message -> merged.putIfAbsent(message.clientMsgId, message) }
        return merged.values.sortedWith(messageDisplayOrder)
    }
}

internal data class MessageWindowLeaseState(
    val messages: Flow<List<Message>>,
    val hasMore: StateFlow<Boolean>,
)

/** 常驻引导与同步 SQLite 短读的唯一事实源。 */
internal fun loadBoundedInitialMessages(
    queries: AppDatabaseQueries,
    chatId: String,
    limit: Int,
    toModel: (com.virjar.tk.shared.database.Message) -> Message,
): List<Message> {
    require(limit > 0) { "message limit must be positive" }
    val authorityAnchor = queries.selectLatestAuthoritativeMessagesByChat(chatId, 1L).executeAsList()
    val optimisticCapacity = limit - if (authorityAnchor.isEmpty()) 0 else 1
    val optimistic = queries.selectOptimisticMessagesByChat(
        chatId,
        optimisticCapacity.coerceAtLeast(0).toLong(),
    ).executeAsList()
    val authoritativeCapacity = (limit - optimistic.size).coerceAtLeast(0)
    val authoritative = if (authoritativeCapacity == 1 && authorityAnchor.isNotEmpty()) {
        authorityAnchor
    } else {
        queries.selectLatestAuthoritativeMessagesByChat(chatId, authoritativeCapacity.toLong()).executeAsList()
    }
    return (optimistic + authoritative).map(toModel).sortedWith(messageDisplayOrder)
}

internal val messageDisplayOrder = compareByDescending<Message> {
    if (it.serverSeq > 0L) it.serverSeq else Long.MAX_VALUE
}.thenByDescending { it.timestamp }
