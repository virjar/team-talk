package com.virjar.tk.shared.testkit

import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.protocol.model.MessageReactionSummary
import com.virjar.tk.protocol.MessageReactionEventPayload
import com.virjar.tk.shared.client.KeyedProjectionSnapshotGate
import com.virjar.tk.shared.client.ProjectionSnapshotLease
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * [FakeLocalCache] 的表情回应内存投影：行级 delta、权威快照与目录观察，
 * 测试可用 [rows] 断言精确行。
 */
internal class FakeMessageReactionProjection {
    private val rowsByChat = LinkedHashMap<String, MutableSet<Triple<Long, String, String>>>()
    private val flows = LinkedHashMap<MutableStateFlow<Map<Long, List<MessageReactionGroup>>>, String>()
    private val lock = Any()
    private val snapshots = KeyedProjectionSnapshotGate("fake message reactions")

    fun applyDelta(payload: MessageReactionEventPayload) {
        synchronized(lock) {
            snapshots.invalidate(payload.chatId)
            val rows = rowsByChat.getOrPut(payload.chatId) { linkedSetOf() }
            val key = Triple(payload.serverSeq, payload.emoji, payload.actorUid)
            if (payload.added) rows.add(key) else rows.remove(key)
            publishLocked(payload.chatId)
        }
    }

    fun beginSnapshot(chatId: String): ProjectionSnapshotLease = synchronized(lock) {
        snapshots.begin(chatId)
    }

    fun applySnapshot(
        lease: ProjectionSnapshotLease,
        chatId: String,
        fromSeq: Long,
        toSeq: Long,
        summaries: List<MessageReactionSummary>,
    ): Boolean = synchronized(lock) {
        require(fromSeq in 1L..toSeq) { "回应聚合区间非法" }
        require(summaries.all { it.serverSeq in fromSeq..toSeq }) { "回应快照包含区间外消息" }
        require(summaries.map(MessageReactionSummary::serverSeq).toSet().size == summaries.size)
        if (!snapshots.consumeIfCurrent(lease, chatId)) return@synchronized false
        val rows = rowsByChat.getOrPut(chatId) { linkedSetOf() }
        rows.removeAll { it.first in fromSeq..toSeq }
        summaries.forEach { summary ->
            summary.groups.forEach { group ->
                group.reactorUids.forEach { uid ->
                    rows.add(Triple(summary.serverSeq, group.emoji, uid))
                }
            }
        }
        publishLocked(chatId)
        true
    }

    fun abandonSnapshot(lease: ProjectionSnapshotLease): Boolean = synchronized(lock) {
        snapshots.abandon(lease)
    }

    fun reset() = synchronized(lock) {
        snapshots.reset()
        rowsByChat.clear()
        flows.keys.forEach { it.value = emptyMap() }
    }

    fun deleteChat(chatId: String) = synchronized(lock) {
        snapshots.invalidate(chatId)
        rowsByChat.remove(chatId)
        publishLocked(chatId)
    }

    fun clearMessage(chatId: String, serverSeq: Long) {
        synchronized(lock) {
            snapshots.invalidate(chatId)
            rowsByChat[chatId]?.removeAll { it.first == serverSeq }
            publishLocked(chatId)
        }
    }

    fun observe(chatId: String): Flow<Map<Long, List<MessageReactionGroup>>> = flow {
        val state = synchronized(lock) {
            MutableStateFlow(aggregate(chatId)).also { flows[it] = chatId }
        }
        try {
            emitAll(state)
        } finally {
            synchronized(lock) { flows.remove(state) }
        }
    }

    fun rows(chatId: String): Set<Triple<Long, String, String>> = synchronized(lock) {
        rowsByChat[chatId].orEmpty().toSet()
    }

    private fun aggregate(chatId: String): Map<Long, List<MessageReactionGroup>> =
        rowsByChat[chatId].orEmpty()
            .groupBy(Triple<Long, String, String>::first)
            .map { (seq, keys) ->
                seq to keys.groupBy(Triple<Long, String, String>::second)
                    .map { (emoji, same) -> MessageReactionGroup(emoji, same.map { it.third }) }
            }
            .toMap()

    private fun publishLocked(chatId: String) {
        val aggregate = aggregate(chatId)
        flows.forEach { (flow, ownerChatId) -> if (ownerChatId == chatId) flow.value = aggregate }
    }
}
