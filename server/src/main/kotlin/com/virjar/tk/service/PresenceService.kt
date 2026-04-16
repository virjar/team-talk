package com.virjar.tk.service

import com.virjar.tk.protocol.payload.PresencePayload
import com.virjar.tk.store.ContactStore
import com.virjar.tk.tcp.ClientRegistry

/**
 * 在线状态推送服务：用户上线/下线时广播 PRESENCE 包给其所有好友。
 */
object PresenceService {
    private lateinit var contactStore: ContactStore

    fun init(contactStore: ContactStore) {
        this.contactStore = contactStore
    }

    /**
     * 异步广播上线通知（fire-and-forget）。
     * 不阻塞调用线程，广播失败不影响认证流程。
     */
    fun postBroadcastOnline(uid: String) {
        val friendUidSet = contactStore.getFriendUids(uid)
        for (friendUid in friendUidSet) {
            sendPresence(friendUid, uid, STATUS_ONLINE, 0L)
        }
    }

    /**
     * 异步广播下线通知（fire-and-forget）。
     */
    fun postBroadcastOffline(uid: String) {
        val lastSeenAt = System.currentTimeMillis()
        val friendUidSet = contactStore.getFriendUids(uid)
        for (friendUid in friendUidSet) {
            sendPresence(friendUid, uid, STATUS_OFFLINE, lastSeenAt)
        }
    }

    private fun sendPresence(targetUid: String, presenceUid: String, status: Byte, lastSeenAt: Long) {
        ClientRegistry.doWithUserAgentGroup(targetUid) { userAgentGroup ->
            val proto = PresencePayload(presenceUid, status, lastSeenAt)
            for (agent in userAgentGroup.allAgent.values) {
                if (!agent.isActive) continue
                agent.send(proto)
            }
        }
    }

    private const val STATUS_OFFLINE: Byte = 0
    private const val STATUS_ONLINE: Byte = 1
}
