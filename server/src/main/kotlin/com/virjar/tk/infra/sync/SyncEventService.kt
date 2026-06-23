package com.virjar.tk.infra.sync

import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.NotifyPayload
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * 事件同步服务。
 * 数据变更后写入 sync_events 表，同时推送给在线用户。
 */
class SyncEventService(
    private val clientRegistry: ClientRegistry,
) {
    private val logger = LoggerFactory.getLogger("SyncEventService")

    /**
     * 向单个用户推送通知。
     */
    suspend fun emitEvent(uid: String, notifyType: NotifyType, payload: IProto) {
        val eventId = persistEvent(uid, notifyType, payload)
        pushToUser(uid, NotifyPayload(eventId, notifyType.code, ProtoCodec.encode(payload)))
    }

    /**
     * 向多个用户推送同一通知。
     */
    suspend fun emitEvents(uids: List<String>, notifyType: NotifyType, payload: IProto) {
        val encoded = ProtoCodec.encode(payload)
        for (uid in uids) {
            val eventId = persistEvent(uid, notifyType, encoded)
            pushToUser(uid, NotifyPayload(eventId, notifyType.code, encoded))
        }
    }

    /**
     * 查询用户在某个 eventId 之后的所有事件（离线补发）。
     */
    fun getEventsAfter(uid: String, afterEventId: Long, limit: Int = 100): List<NotifyPayload> {
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

    private fun persistEvent(uid: String, notifyType: NotifyType, payload: IProto): Long {
        return persistEvent(uid, notifyType, ProtoCodec.encode(payload))
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
        val agents = clientRegistry.getAgents(uid)
        if (agents.isEmpty()) return
        for (agent in agents) {
            try {
                agent.write(notify)
            } catch (e: Exception) {
                logger.warn("Failed to push event to uid=$uid", e)
            }
        }
    }

}
