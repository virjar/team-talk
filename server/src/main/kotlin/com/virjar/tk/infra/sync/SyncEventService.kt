package com.virjar.tk.infra.sync

import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.event.SyncBatchResult
import com.virjar.tk.domain.event.SyncEventReader
import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyContracts
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 事件同步服务。
 * 数据变更后写入 sync_events 表，同时推送给在线用户。
 */
class SyncEventService(
    private val clientRegistry: ClientRegistry,
) : EventPublisher, SyncEventReader {
    private val logger = LoggerFactory.getLogger("SyncEventService")
    /**
     * 持久化 + live push 与最终的“二次查空 + 激活”共用有界条带锁。
     * 同 uid 始终落到同一把锁；不同 uid 的哈希碰撞只会降低并发，不影响正确性。
     */
    private val deliveryGates = Array(DELIVERY_GATE_STRIPES) { Mutex() }

    private fun deliveryGate(uid: String): Mutex =
        deliveryGates[(uid.hashCode() and Int.MAX_VALUE) % deliveryGates.size]

    /**
     * 向单个用户推送通知。
     */
    override suspend fun emitEvent(uid: String, notifyType: NotifyType, payload: IProto) {
        assertContract(notifyType, payload)
        val encoded = ProtoCodec.encode(payload)
        deliveryGate(uid).withLock {
            val eventId = persistEvent(uid, notifyType, encoded)
            pushToUser(uid, NotifyPayload(eventId, notifyType.code, encoded))
        }
    }

    /**
     * 向多个用户推送同一通知。
     */
    override suspend fun emitEvents(uids: List<String>, notifyType: NotifyType, payload: IProto) {
        assertContract(notifyType, payload)
        val encoded = ProtoCodec.encode(payload)
        for (uid in uids.distinct()) {
            deliveryGate(uid).withLock {
                val eventId = persistEvent(uid, notifyType, encoded)
                pushToUser(uid, NotifyPayload(eventId, notifyType.code, encoded))
            }
        }
    }

    /**
     * 契约校验：emit 的 payload 实际类型必须与 [NotifyContracts] 登记一致。
     * 错配在服务端当场抛异常（测试期即失败），不再漏到客户端集成时才以
     * "UI 数据错乱/解析异常"的形式爆发。
     */
    private fun assertContract(notifyType: NotifyType, payload: IProto) {
        val reader = NotifyContracts.payloads[notifyType] ?: return // 豁免类型不校验
        val expected = NotifyContracts.expectedPayloadClassName(notifyType, reader::class.java.name)
        val actual = payload::class.java.name
        require(expected == actual) {
            "Notify contract violation: $notifyType expects payload $expected but got $actual. " +
                "Fix the emit site or update NotifyContracts."
        }
    }

    override suspend fun emitTransient(uid: String, notifyType: NotifyType, payload: IProto) {
        assertContract(notifyType, payload)
        deliveryGate(uid).withLock {
            pushToUser(uid, NotifyPayload(0, notifyType.code, ProtoCodec.encode(payload)))
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
                .where { (SyncEvents.uid eq uid) and (SyncEvents.id greater afterEventId) }
                .orderBy(SyncEvents.id)
                .limit(limit)
                .map { row ->
                    NotifyPayload(
                        eventId = row[SyncEvents.id].value,
                        notifyType = row[SyncEvents.eventType],
                        payload = row[SyncEvents.payload],
                    )
                }
        }
    }

    /**
     * 普通分页不持有 delivery gate。只有第一次读空后才进入门闩并二次查询：
     *
     * - live 事件先获得门闩：它先持久化，二次查询必然看见；
     * - 激活先获得门闩：二次查询为空，SYNC_READY/注册完成后门闩才释放，
     *   后续事件只能作为 live NOTIFY 排在 READY 后。
     */
    override suspend fun nextBatchOrActivate(
        uid: String,
        afterEventId: Long,
        limit: Int,
        activate: suspend () -> Boolean,
    ): SyncBatchResult {
        require(limit in 1..MAX_QUERY_EVENTS) { "sync limit must be in 1..$MAX_QUERY_EVENTS" }
        // A cursor is an acknowledgement of a durable event previously projected for this uid,
        // not an arbitrary global sequence number. Accepting a guessed/high cursor would let a
        // corrupt client skip both the current backlog and future events whose global ids remain
        // below that value. Retention is intentionally disabled in this development epoch, so
        // every legitimate non-zero cursor must still have an owner row here.
        if (!isOwnedCursor(uid, afterEventId)) return SyncBatchResult.InvalidCursor
        val first = getEventsAfter(uid, afterEventId, limit)
        if (first.isNotEmpty()) return SyncBatchResult.Events(wireBoundedPage(first))

        return deliveryGate(uid).withLock {
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
            SyncEvents.selectAll()
                .where { (SyncEvents.id eq eventId) and (SyncEvents.uid eq uid) }
                .limit(1)
                .any()
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
        private const val DELIVERY_GATE_STRIPES = 64
    }

    private fun persistEvent(uid: String, notifyType: NotifyType, encoded: ByteArray): Long {
        return transaction {
            SyncEvents.insert {
                it[SyncEvents.uid] = uid
                it[SyncEvents.eventType] = notifyType.code
                it[SyncEvents.payload] = encoded
                it[SyncEvents.createdAt] = System.currentTimeMillis()
            } get SyncEvents.id
        }.value
    }

    private suspend fun pushToUser(uid: String, notify: NotifyPayload) {
        try {
            clientRegistry.push(uid, notify)
        } catch (e: Exception) {
            logger.warn("Failed to push event to uid=$uid", e)
        }
    }

}
