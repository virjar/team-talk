package com.virjar.tk.domain.presence

import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.PresencePayload

/**
 * 在线状态推送服务：用户上线/下线时广播给其所有好友。
 */
class PresenceService(
    private val contactStore: ContactStore,
    private val events: EventPublisher,
) {
    suspend fun broadcastOnline(uid: String) {
        val friendUids = contactStore.getFriendUids(uid)
        for (friendUid in friendUids) {
            sendPresence(friendUid, uid, STATUS_ONLINE, 0L)
        }
    }

    suspend fun broadcastOffline(uid: String) {
        val lastSeenAt = System.currentTimeMillis()
        val friendUids = contactStore.getFriendUids(uid)
        for (friendUid in friendUids) {
            sendPresence(friendUid, uid, STATUS_OFFLINE, lastSeenAt)
        }
    }

    private suspend fun sendPresence(targetUid: String, presenceUid: String, status: Byte, lastSeenAt: Long) {
        events.emitTransient(
            targetUid,
            NotifyType.PRESENCE,
            PresencePayload(presenceUid, status, lastSeenAt),
        )
    }

    companion object {
        const val STATUS_OFFLINE: Byte = 0
        const val STATUS_ONLINE: Byte = 1
    }
}
