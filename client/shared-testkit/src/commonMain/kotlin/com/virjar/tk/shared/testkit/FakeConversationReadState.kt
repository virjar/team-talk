package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.PendingConversationRead
import com.virjar.tk.shared.client.LocalOutboxCapacityDimension
import com.virjar.tk.shared.client.LocalOutboxCapacityExceededException
import com.virjar.tk.shared.client.LocalOutboxKind
import com.virjar.tk.shared.client.MAX_CONVERSATION_READ_OUTBOX_ENTRIES
import com.virjar.tk.protocol.model.Conversation
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * FakeLocalCache 的会话已读投影与持久化可靠发件箱的对应实现。
 *
 * 所有者用其会话锁串行化每次调用。把这份状态放在一个聚焦的协作者中，
 * 可以避免本就宽泛的测试替身再积累一个独立的关注点。
 */
internal class FakeConversationReadState(
    private val conversations: MutableStateFlow<List<Conversation>>,
    private val onProjectionChanged: (String) -> Unit,
) {
    private val pending = mutableMapOf<String, Long>()

    fun enqueue(chatId: String, readSeq: Long): Long {
        require(readSeq > 0L) { "readSeq must be positive" }
        if (chatId !in pending && pending.size >= MAX_CONVERSATION_READ_OUTBOX_ENTRIES) {
            throw LocalOutboxCapacityExceededException(
                LocalOutboxKind.CONVERSATION_READ,
                LocalOutboxCapacityDimension.ENTRY_COUNT,
                MAX_CONVERSATION_READ_OUTBOX_ENTRIES.toLong(),
            )
        }
        val desired = maxOf(pending[chatId] ?: 0L, readSeq)
        pending[chatId] = desired
        updateProjection(chatId, desired)
        // 即使在会话行尚不存在时，待处理事实也必须对过期快照设门禁。
        onProjectionChanged(chatId)
        return desired
    }

    fun pending(): List<PendingConversationRead> = pending.entries
        .sortedBy(Map.Entry<String, Long>::key)
        .map { (chatId, readSeq) -> PendingConversationRead(chatId, readSeq) }

    fun pending(chatId: String): PendingConversationRead? =
        pending[chatId]?.let { readSeq -> PendingConversationRead(chatId, readSeq) }

    fun chatIds(): Set<String> = pending.keys.toSet()

    fun watermark(chatId: String): Long? = pending[chatId]

    fun acknowledge(chatId: String, readSeq: Long) {
        require(readSeq > 0L) { "readSeq must be positive" }
        if ((pending[chatId] ?: Long.MAX_VALUE) <= readSeq) pending.remove(chatId)
    }

    fun remove(chatId: String) {
        pending.remove(chatId)
    }

    private fun updateProjection(chatId: String, readSeq: Long) {
        conversations.value = conversations.value.map { conversation ->
            if (conversation.chatId != chatId) return@map conversation
            val mergedReadSeq = maxOf(conversation.readSeq, readSeq)
            val mergedUnread = (conversation.lastSeq - mergedReadSeq)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()
            conversation.copy(unreadCount = mergedUnread, readSeq = mergedReadSeq)
        }
    }
}
