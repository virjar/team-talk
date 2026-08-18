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
}
