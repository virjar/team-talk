package com.virjar.tk.domain.presence

import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.NotifyPayload
import org.slf4j.LoggerFactory

/**
 * 在线状态推送服务：用户上线/下线时广播给其所有好友。
 */
class PresenceService(
    private val contactStore: ContactStore,
    private val clientRegistry: ClientRegistry,
) {
    private val logger = LoggerFactory.getLogger("PresenceService")

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
        val agents = clientRegistry.getAgents(targetUid)
        for (agent in agents) {
            try {
                val payload = PresencePayload(presenceUid, status, lastSeenAt)
                val notify = NotifyPayload(0, NotifyType.PRESENCE.code, ProtoCodec.encode(payload))
                agent.write(notify)
            } catch (e: Exception) {
                logger.debug("Failed to send presence to {}: {}", targetUid, e.message)
            }
        }
    }

    companion object {
        const val STATUS_OFFLINE: Byte = 0
        const val STATUS_ONLINE: Byte = 1
    }
}

/** Presence 通知载荷 */
class PresencePayload(
    val uid: String,
    val status: Byte,
    val lastSeenAt: Long,
) : com.virjar.tk.protocol.IProto {
    override fun writeTo(buf: com.virjar.tk.protocol.PacketBuffer) {
        buf.writeString(uid)
        buf.writeByte(status.toInt())
        buf.writeVarLong(lastSeenAt)
    }
}
