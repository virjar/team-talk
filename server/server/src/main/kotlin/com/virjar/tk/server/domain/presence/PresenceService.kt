package com.virjar.tk.server.domain.presence

import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.event.TransientEventPublisher
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.PresencePayload

/**
 * 在线状态推送服务：用户上线/下线时广播给其所有好友。
 */
class PresenceService(
    private val contacts: ContactRepository,
    private val events: TransientEventPublisher,
) {
    suspend fun broadcast(transition: PresenceTransition) {
        val payload = PresencePayload(
            uid = transition.uid,
            status = if (transition.online) PresencePayload.STATUS_ONLINE else PresencePayload.STATUS_OFFLINE,
            lastSeenAt = if (transition.online) 0L else transition.occurredAt,
            serverEpoch = transition.serverEpoch,
            revision = transition.revision,
        )
        val friendUids = contacts.listFriendUids(transition.uid)
        for (friendUid in friendUids) {
            sendPresence(friendUid, payload)
        }
    }

    private suspend fun sendPresence(targetUid: String, payload: PresencePayload) {
        events.emitTransient(
            targetUid,
            NotifyType.PRESENCE,
            payload,
        )
    }
}
