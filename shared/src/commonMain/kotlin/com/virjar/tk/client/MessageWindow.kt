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
 * 所有操作不持有 LocalCacheImpl 引用，通过 [queries] 直接访问 DB。
 */
internal class MessageWindow(
    private val chatId: String,
    private val queries: AppDatabaseQueries,
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

    // 消息映射函数（从 LocalCacheImpl 传入，复用 toModel 逻辑）
    private val toModelFn = toModel

    init {
        loadInitialWindow()
    }

    private fun loadInitialWindow() {
        val rows = queries.selectMessagesByChat(chatId, windowSize.toLong()).executeAsList()
        val msgs = rows.map { toModelFn(it) }.sortedWith(messageOrder)
        _messages.value = msgs
        historyCursor = oldestServerSeq(msgs)
        refreshHasMore(msgs)
    }

    private fun refreshHasMore(currentMsgs: List<Message>) {
        if (serverPageAnchored) {
            // Local rows outside the authoritative response chain cannot prove continuity. The
            // ChatViewModel's remoteHasMore drives the next server page instead.
            _hasMore.value = false
            return
        }
        val cursor = historyCursor ?: oldestServerSeq(currentMsgs)
        _hasMore.value = cursor != null &&
            queries.selectMessagesByChatBefore(chatId, cursor, 1L).executeAsList().isNotEmpty()
    }

    override fun loadMore(pageSize: Int) = synchronized(stateLock) {
        if (serverPageAnchored) return@synchronized
        val current = _messages.value
        val oldestSeq = historyCursor ?: oldestServerSeq(current) ?: return@synchronized
        if (!_hasMore.value) return@synchronized

        val olderRows = queries.selectMessagesByChatBefore(chatId, oldestSeq, pageSize.toLong()).executeAsList()
        val existingIds = current.asSequence().map(Message::clientMsgId).toHashSet()
        val older = olderRows.map(toModelFn).filter { existingIds.add(it.clientMsgId) }
        val merged = (current + older).sortedWith(messageOrder)
        _messages.value = merged
        historyCursor = oldestServerSeq(merged)
        refreshHasMore(merged)
    }

    /**
     * Apply one RPC history response as an atomic, server-proven page. Sequence numbers are
     * cursors, not a promise of `n - 1` adjacency: legal holes inside or between pages are kept.
     */
    fun applyHistoryPage(page: List<Message>, resetResidentWindow: Boolean) = synchronized(stateLock) {
        val startNewChain = resetResidentWindow || !serverPageAnchored
        val base = if (startNewChain) {
            val pageMaxSeq = page.asSequence().map(Message::serverSeq).filter { it > 0L }.maxOrNull()
            _messages.value.filter { message ->
                message.serverSeq <= 0L || (pageMaxSeq != null && message.serverSeq > pageMaxSeq)
            }
        } else {
            _messages.value
        }
        val merged = mergeByClientId(base, page)
        serverPageAnchored = true
        historyCursor = oldestServerSeq(merged)
        _messages.value = merged
        _hasMore.value = false
    }

    /**
     * 新消息到达（NOTIFY 推送）或发送时调用，更新内存窗口。
     * 如果窗口不存在该消息则插入到最前面（最新），存在则更新。
     */
    fun upsert(message: Message) = synchronized(stateLock) {
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
        trimIfOversized(current)
        _messages.value = current
        historyCursor = oldestServerSeq(current)
        refreshHasMore(current)
    }

    /**
     * 更新消息状态（serverSeq / sendStatus / 任意变换，如上传进度）。
     */
    fun updateMessage(
        clientMsgId: String,
        serverSeq: Long? = null,
        sendStatus: Int? = null,
        transform: (Message.() -> Message)? = null,
    ) = synchronized(stateLock) {
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

    fun deleteMessage(clientMsgId: String) = synchronized(stateLock) {
        val current = _messages.value.filter { it.clientMsgId != clientMsgId }
        _messages.value = current
        historyCursor = oldestServerSeq(current)
        refreshHasMore(current)
    }

    /** 窗口超过 windowSize * 2 时裁剪最老的消息（保留 hasMore=true）。 */
    private fun trimIfOversized(list: MutableList<Message>) {
        if (list.size > maxCapacity) {
            val dropCount = list.size - windowSize
            repeat(dropCount) { list.removeAt(list.lastIndex) }
        }
    }

    /** 当前窗口快照（用于 getMessages 同步访问）。 */
    fun snapshot(limit: Int): List<Message> = synchronized(stateLock) {
        _messages.value.take(limit)
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
