package com.virjar.tk.domain.event

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.payload.NotifyPayload

/**
 * Domain-facing event boundary. Durable events participate in offline sync;
 * transient events are delivered only to currently connected sessions.
 */
interface EventPublisher {
    suspend fun emitEvent(uid: String, notifyType: NotifyType, payload: IProto)
    suspend fun emitEvents(uids: List<String>, notifyType: NotifyType, payload: IProto)
    suspend fun emitTransient(uid: String, notifyType: NotifyType, payload: IProto)
}

/** Read side used by the TCP adapter when a client resumes a session. */
interface SyncEventReader {
    fun getEventsAfter(uid: String, afterEventId: Long, limit: Int = 100): List<NotifyPayload>

    /**
     * Return one replay page bounded by both count and wire bytes, or atomically activate live
     * delivery after a second empty check under the same per-user gate used by persistence/push.
     * A single event which only fits as standalone NOTIFY may be returned as a one-item page.
     */
    suspend fun nextBatchOrActivate(
        uid: String,
        afterEventId: Long,
        limit: Int,
        activate: suspend () -> Boolean,
    ): SyncBatchResult
}

sealed interface SyncBatchResult {
    data class Events(val events: List<NotifyPayload>) : SyncBatchResult
    data object Activated : SyncBatchResult
    data object ConnectionClosed : SyncBatchResult
    /** The claimed durable cursor is neither zero nor an event owned by this user. */
    data object InvalidCursor : SyncBatchResult
}
