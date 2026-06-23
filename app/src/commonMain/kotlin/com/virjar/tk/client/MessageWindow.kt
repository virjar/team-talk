package com.virjar.tk.client

import app.cash.sqldelight.db.SqlDriver
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
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    override val messages: Flow<List<Message>> = _messages.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    override val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    // 消息映射函数（从 LocalCacheImpl 传入，复用 toModel 逻辑）
    private val toModelFn = toModel

    init {
        loadInitialWindow()
    }

    private fun loadInitialWindow() {
        val rows = queries.selectMessagesByChat(chatId, windowSize.toLong()).executeAsList()
        val msgs = rows.map { toModelFn(it) }
        _messages.value = msgs
        refreshHasMore(msgs)
    }

    private fun refreshHasMore(currentMsgs: List<Message>) {
        if (currentMsgs.isEmpty()) {
            _hasMore.value = false
            return
        }
        val total = queries.countMessagesByChat(chatId).executeAsOne()
        _hasMore.value = currentMsgs.size < total
    }

    override fun loadMore(pageSize: Int) {
        val current = _messages.value
        val oldestSeq = current.lastOrNull()?.serverSeq ?: return
        if (oldestSeq <= 0L) return
        if (!_hasMore.value) return

        val olderRows = queries.selectMessagesByChatBefore(chatId, oldestSeq, pageSize.toLong()).executeAsList()
        val older = olderRows.map { toModelFn(it) }
        _messages.value = current + older  // 追加到末尾（更老的消息）
        if (older.size < pageSize) _hasMore.value = false
    }

    /**
     * 新消息到达（NOTIFY 推送）或发送时调用，更新内存窗口。
     * 如果窗口不存在该消息则插入到最前面（最新），存在则更新。
     */
    fun upsert(message: Message) {
        val current = _messages.value.toMutableList()
        val idx = current.indexOfFirst { it.clientMsgId == message.clientMsgId }
        if (idx >= 0) {
            current[idx] = message
        } else {
            current.add(0, message)
            trimIfOversized(current)
        }
        _messages.value = current
    }

    /**
     * 更新消息状态（serverSeq / sendStatus）。
     */
    fun updateMessage(clientMsgId: String, serverSeq: Long? = null, sendStatus: Int? = null) {
        val current = _messages.value.toMutableList()
        val idx = current.indexOfFirst { it.clientMsgId == clientMsgId }
        if (idx < 0) return
        val msg = current[idx]
        current[idx] = msg.copy(
            serverSeq = serverSeq ?: msg.serverSeq,
            sendStatus = sendStatus ?: msg.sendStatus,
        )
        _messages.value = current
    }

    fun deleteMessage(clientMsgId: String) {
        _messages.value = _messages.value.filter { it.clientMsgId != clientMsgId }
    }

    /** 窗口超过 windowSize * 2 时裁剪最老的消息（保留 hasMore=true）。 */
    private fun trimIfOversized(list: MutableList<Message>) {
        val maxCapacity = windowSize * 2
        if (list.size > maxCapacity) {
            val dropCount = list.size - windowSize
            repeat(dropCount) { list.removeAt(list.lastIndex) }
            _hasMore.value = true
        }
    }

    /** 当前窗口快照（用于 getMessages 同步访问）。 */
    fun snapshot(limit: Int): List<Message> = _messages.value.take(limit)
}
