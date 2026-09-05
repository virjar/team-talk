package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.protocol.model.MessageReactionSummary
import com.virjar.tk.protocol.MessageReactionEventPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * 行级表情回应投影（CLIENT-05）。
 *
 * 每行是服务端权威 delta/快照的精确结果；观察者只得到派生聚合（seq → emoji → reactors），
 * 增删语义与计数权威永远在服务端。删除聊天或回收过期消息时，在同一事务内清掉孤立回应，再通过
 * [refreshResidentAfterPrune] 重载当前观察者的投影。
 *
 * Resident 与消息窗口一致：只有至少一个观察者的 chat 保持内存聚合，零观察者条目立即
 * 释放；上限与 [LocalCache.MAX_ACTIVE_CHATS] 对齐，全部活跃时拒绝新的观察（fail loud）。
 */
internal class LocalMessageReactionStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val maxActiveChats: Int = LocalCache.MAX_ACTIVE_CHATS,
) {
    private val residents = LinkedHashMap<String, ReactionResident>()
    private val snapshots = KeyedProjectionSnapshotGate("message reactions")

    private class ReactionResident {
        // seq -> (emoji -> uids)，发布前派生为不可变聚合
        val bySeq = LinkedHashMap<Long, LinkedHashMap<String, LinkedHashSet<String>>>()
        val flow = RetirableProjectionState<Map<Long, List<MessageReactionGroup>>>(emptyMap())
        var observers = 0
    }

    init {
        check(maxActiveChats > 0) { "maxActiveChats must be positive" }
    }

    /** MESSAGE_REACTION 按事件顺序投影；同一 delta 重放幂等，不能越过在途快照。 */
    fun applyReactionDelta(payload: MessageReactionEventPayload) = cacheUseGate.use {
        synchronized(stateLock) {
            snapshots.invalidate(payload.chatId)
            if (payload.added) {
                queries.insertMessageReaction(payload.chatId, payload.serverSeq, payload.emoji, payload.actorUid)
            } else {
                queries.deleteMessageReaction(payload.chatId, payload.serverSeq, payload.emoji, payload.actorUid)
            }
            applyResidentDeltaLocked(payload.chatId, payload.serverSeq, payload.emoji, payload.actorUid, payload.added)
        }
    }

    fun beginSnapshot(chatId: String): ProjectionSnapshotLease = cacheUseGate.use {
        synchronized(stateLock) { snapshots.begin(chatId) }
    }

    /** listReactions 成功代表完整区间，不能把省略的无回应消息误当作“没有更新”。 */
    fun applySnapshot(
        lease: ProjectionSnapshotLease,
        chatId: String,
        fromSeq: Long,
        toSeq: Long,
        summaries: List<MessageReactionSummary>,
    ): Boolean = cacheUseGate.runIfOpen {
        require(fromSeq in 1L..toSeq) { "回应聚合区间非法" }
        require(summaries.all { it.serverSeq in fromSeq..toSeq }) { "回应快照包含区间外消息" }
        require(summaries.map(MessageReactionSummary::serverSeq).toSet().size == summaries.size) {
            "同一消息的回应快照不能重复出现"
        }
        synchronized(stateLock) {
            if (!snapshots.consumeIfCurrent(lease, chatId)) return@synchronized false
            queries.transaction {
                queries.deleteMessageReactionsInRange(chatId, fromSeq, toSeq)
                summaries.forEach { summary ->
                    summary.groups.forEach { group ->
                        group.reactorUids.forEach { uid ->
                            queries.insertMessageReaction(chatId, summary.serverSeq, group.emoji, uid)
                        }
                    }
                }
            }
            applyResidentSnapshotLocked(chatId, fromSeq, toSeq, summaries)
            true
        }
    }

    fun abandonSnapshot(lease: ProjectionSnapshotLease): Boolean = cacheUseGate.runIfOpen {
        synchronized(stateLock) { snapshots.abandon(lease) }
    }

    /** 撤回消息（事件或历史页）时清理该消息的回应投影。 */
    fun clearMessageReactions(chatId: String, serverSeq: Long) {
        cacheUseGate.use {
            synchronized(stateLock) {
                snapshots.invalidate(chatId)
                queries.deleteMessageReactionsForSeq(chatId, serverSeq)
                residents[chatId]?.let { resident ->
                    resident.bySeq.remove(serverSeq)
                    publishLocked(resident)
                }
                Unit
            }
        }
    }

    fun observeMessageReactions(chatId: String): Flow<Map<Long, List<MessageReactionGroup>>> = cacheUseGate.use {
        flow {
            val resident = acquireResidentLocked(chatId)
            try {
                emitAll(resident.flow.observe())
            } finally {
                releaseResidentLocked(chatId, resident)
            }
        }
    }

    /** 调用方已在 checkpoint/reset 事务中清空回应行；提交后再失效请求并发布空投影。 */
    fun publishServerProjectionResetLocked() {
        snapshots.reset()
        residents.values.forEach { resident ->
            resident.bySeq.clear()
            resident.flow.value = emptyMap()
        }
    }

    /** 调用方在缓存退役期间持有 [stateLock]。 */
    fun closeResidentsLocked() {
        snapshots.reset()
        residents.values.forEach { resident -> resident.flow.retire(emptyMap()) }
        residents.clear()
    }

    /** 消息删除提交后，重载该聊天仍被观察的回应投影。 */
    fun refreshResidentAfterPrune(chatId: String) = cacheUseGate.use {
        synchronized(stateLock) {
            snapshots.invalidate(chatId)
            val resident = residents[chatId] ?: return@synchronized
            resident.bySeq.clear()
            queries.selectMessageReactionsForChat(chatId).executeAsList().forEach { row ->
                val groups = resident.bySeq.getOrPut(row.server_seq) { LinkedHashMap() }
                groups.getOrPut(row.emoji) { LinkedHashSet() }.add(row.uid)
            }
            publishLocked(resident)
        }
    }

    private fun applyResidentDeltaLocked(
        chatId: String,
        serverSeq: Long,
        emoji: String,
        uid: String,
        add: Boolean,
    ) {
        val resident = residents[chatId] ?: return
        val groups = resident.bySeq.getOrPut(serverSeq) { LinkedHashMap() }
        val reactors = groups.getOrPut(emoji) { LinkedHashSet() }
        if (add) reactors.add(uid) else reactors.remove(uid)
        if (reactors.isEmpty()) groups.remove(emoji)
        if (groups.isEmpty()) resident.bySeq.remove(serverSeq)
        publishLocked(resident)
    }

    private fun applyResidentSnapshotLocked(
        chatId: String,
        fromSeq: Long,
        toSeq: Long,
        summaries: List<MessageReactionSummary>,
    ) {
        val resident = residents[chatId] ?: return
        resident.bySeq.keys.removeAll { it in fromSeq..toSeq }
        summaries.forEach { summary ->
            val groups = LinkedHashMap<String, LinkedHashSet<String>>()
            summary.groups.forEach { group ->
                groups[group.emoji] = LinkedHashSet(group.reactorUids)
            }
            if (groups.isEmpty()) {
                resident.bySeq.remove(summary.serverSeq)
            } else {
                resident.bySeq[summary.serverSeq] = groups
            }
        }
        publishLocked(resident)
    }

    private fun acquireResidentLocked(chatId: String): ReactionResident = cacheUseGate.use {
        synchronized(stateLock) {
            val existing = residents[chatId]
            if (existing != null) {
                existing.observers += 1
                return@synchronized existing
            }
            // 最后一个观察者退出时立即移除，所以表内没有可供 LRU 驱逐的闲置项。
            check(residents.size < maxActiveChats) {
                "All $maxActiveChats resident reaction chats have active observers"
            }
            val resident = ReactionResident()
            queries.selectMessageReactionsForChat(chatId).executeAsList().forEach { row ->
                val groups = resident.bySeq.getOrPut(row.server_seq) { LinkedHashMap() }
                groups.getOrPut(row.emoji) { LinkedHashSet() }.add(row.uid)
            }
            resident.flow.value = resident.bySeq.toAggregates()
            residents[chatId] = resident
            resident.observers = 1
            resident
        }
    }

    private fun releaseResidentLocked(chatId: String, resident: ReactionResident) = cacheUseGate.use {
        synchronized(stateLock) {
            check(resident.observers > 0) { "reaction observer count underflow" }
            resident.observers -= 1
            if (resident.observers == 0 && residents[chatId] === resident) {
                resident.flow.retire(resident.bySeq.toAggregates())
                residents.remove(chatId)
            }
        }
    }

    private fun publishLocked(resident: ReactionResident) {
        resident.flow.value = resident.bySeq.toAggregates()
    }

    private fun LinkedHashMap<Long, LinkedHashMap<String, LinkedHashSet<String>>>.toAggregates():
        Map<Long, List<MessageReactionGroup>> =
        entries.associate { (seq, groups) ->
            seq to groups.entries
                .map { (emoji, uids) -> MessageReactionGroup(emoji, uids.toSortedSet().toList()) }
                .sortedBy(MessageReactionGroup::emoji)
        }
}
