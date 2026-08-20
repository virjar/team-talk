package com.virjar.tk.infra.sync

import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.event.SyncBatchResult
import com.virjar.tk.domain.event.SyncEventReader
import com.virjar.tk.domain.event.requireNotifyContract
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.infra.db.SyncStreams
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

fun interface SyncEventReadHooks {
    suspend fun afterFirstEmpty(uid: String, afterEventId: Long)

    object None : SyncEventReadHooks {
        override suspend fun afterFirstEmpty(uid: String, afterEventId: Long) = Unit
    }
}

/**
 * 事件同步服务。
 * 数据变更后写入 sync_events 表，同时推送给在线用户。
 */
class SyncEventService(
    private val unitOfWork: PgUnitOfWork,
    private val dispatcher: SyncEventDispatcher,
    private val readHooks: SyncEventReadHooks = SyncEventReadHooks.None,
) : EventPublisher, SyncEventReader {
    private val logger = LoggerFactory.getLogger("SyncEventService")

    /**
     * 向单个用户推送通知。
     */
    override suspend fun emitEvent(uid: String, notifyType: NotifyType, payload: IProto) {
        unitOfWork.write {
            appendEvent(uid, notifyType, payload)
        }
        // Preserve the existing publisher's prompt live-delivery behavior. Correctness does not
        // depend on this call: the UoW wake and dispatcher startup scan cover a crash here.
        dispatcher.dispatchPendingForUid(uid)
    }

    /**
     * 向多个用户推送同一通知。
     */
    override suspend fun emitEvents(uids: List<String>, notifyType: NotifyType, payload: IProto) {
        val recipients = uids.distinct()
        if (recipients.isEmpty()) return
        unitOfWork.write {
            recipients.forEach { uid -> appendEvent(uid, notifyType, payload) }
        }
        recipients.sorted().forEach { uid -> dispatcher.dispatchPendingForUid(uid) }
    }

    override suspend fun emitTransient(uid: String, notifyType: NotifyType, payload: IProto) {
        requireNotifyContract(notifyType, payload)
        try {
            dispatcher.deliverTransient(uid, NotifyPayload(0, notifyType.code, ProtoCodec.encode(payload)))
        } catch (error: Exception) {
            logger.warn("Failed to push transient event to uid={}", uid, error)
        }
    }

    /**
     * 查询用户在某个 eventId 之后的所有事件（离线补发）。
     *
     * 当前开发基线虽有显式 SYNC_RESET 自愈，但仍暂不按时间过滤，便于开发期完整重放与诊断。
     * 将来启用保留期时，已被清理的合法旧游标也必须走 InvalidCursor → SYNC_RESET，绝不能
     * 静默 SYNC_READY。
     */
    override fun getEventsAfter(uid: String, afterEventId: Long, limit: Int): List<NotifyPayload> {
        require(afterEventId >= 0L) { "afterEventId must be non-negative" }
        require(limit > 0) { "limit must be positive" }
        return transaction {
            SyncEvents.selectAll()
                .where { (SyncEvents.uid eq uid) and (SyncEvents.streamSeq greater afterEventId) }
                .orderBy(SyncEvents.streamSeq)
                .limit(limit)
                .map { row ->
                    NotifyPayload(
                        eventId = row[SyncEvents.streamSeq],
                        notifyType = row[SyncEvents.eventType],
                        payload = row[SyncEvents.payload],
                    )
                }
        }
    }

    /**
     * 普通分页不持有 delivery gate。只有第一次读空后才进入门闩并二次查询：
     *
     * - 事件先提交：二次查询必然看见（即使 dispatcher 已完成一次 live 尝试）；
     * - 激活先获得门闩：事务可以提交，但 dispatcher 必须等待 SYNC_READY/注册完成，
     *   后续 live NOTIFY 只能排在 READY 后。
     */
    override suspend fun nextBatchOrActivate(
        uid: String,
        afterEventId: Long,
        limit: Int,
        activate: suspend () -> Boolean,
    ): SyncBatchResult {
        require(limit in 1..MAX_QUERY_EVENTS) { "sync limit must be in 1..$MAX_QUERY_EVENTS" }
        // A cursor is an acknowledgement in this authenticated user's contiguous stream, not a
        // process-global ID. Retention is disabled, so every value in 1..lastSeq is durable and a
        // guessed high cursor must reset instead of silently skipping future events.
        if (!isOwnedCursor(uid, afterEventId)) return SyncBatchResult.InvalidCursor
        val first = getEventsAfter(uid, afterEventId, limit)
        if (first.isNotEmpty()) return SyncBatchResult.Events(wireBoundedPage(first))
        readHooks.afterFirstEmpty(uid, afterEventId)

        return dispatcher.withDeliveryGate(uid) {
            val second = getEventsAfter(uid, afterEventId, limit)
            if (second.isNotEmpty()) {
                SyncBatchResult.Events(wireBoundedPage(second))
            } else if (activate()) {
                SyncBatchResult.Activated
            } else {
                SyncBatchResult.ConnectionClosed
            }
        }
    }

    private fun isOwnedCursor(uid: String, eventId: Long): Boolean {
        if (eventId == 0L) return true
        if (eventId < 0L) return false
        return transaction {
            val lastSeq = SyncStreams.selectAll()
                .where { SyncStreams.uid eq uid }
                .limit(1)
                .singleOrNull()
                ?.get(SyncStreams.lastSeq)
                ?: return@transaction false
            eventId <= lastSeq
        }
    }

    /**
     * A page is bounded by both event count and encoded packet bytes. If only the batch count byte
     * prevents the first otherwise-legal event from fitting, return that single event so the TCP
     * adapter can send it as a standalone durable NOTIFY during synchronization.
     */
    private fun wireBoundedPage(events: List<NotifyPayload>): List<NotifyPayload> {
        val prefix = SyncBatchPayload.boundedPrefix(events)
        return if (prefix.isNotEmpty()) prefix else listOf(events.first())
    }

    /**
     * 开发期正确性优先的显式 no-op。
     *
     * 启动维护任务仍可安全调用此方法。虽然协议已经具备可验证的 SYNC_RESET 分支，
     * 开发期仍保留完整历史以便重放；上线前可在专项容量设计中启用有界保留。
     */
    fun cleanupExpiredEvents(): Int = 0

    companion object {
        const val MAX_QUERY_EVENTS = 64
    }

}
