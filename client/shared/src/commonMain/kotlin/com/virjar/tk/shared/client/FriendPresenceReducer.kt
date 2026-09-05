package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.FriendPresenceSnapshot
import com.virjar.tk.protocol.PresencePayload

/**
 * 一个已认证会话的临时好友在线状态的纯不可变合并状态。
 *
 * 快照之前收到的事件私下保存在 [pendingByUid] 中。只有当完整快照后来确认该 uid 仍是好友时才
 * 可见。这里没有任何东西被持久化：重连后从 UNKNOWN 开始，直到新快照到达。
 */
internal data class FriendPresenceReducerState(
    val serverEpoch: String? = null,
    val snapshotRevision: Long? = null,
    val presenceByUid: Map<String, FriendPresence> = emptyMap(),
    private val pendingByUid: Map<String, FriendPresence> = emptyMap(),
) {
    val isSynchronized: Boolean
        get() = snapshotRevision != null

    fun reduce(snapshot: FriendPresenceSnapshot): FriendPresenceReducerState {
        val sameEpoch = snapshot.serverEpoch == serverEpoch
        val currentBaseline = snapshotRevision
        if (sameEpoch && currentBaseline != null && snapshot.revision < currentBaseline) {
            return this
        }

        val previousEvents = if (sameEpoch) {
            presenceByUid + pendingByUid
        } else {
            emptyMap()
        }
        val onlineUids = snapshot.onlineFriendUids.toHashSet()
        val nextPresence = snapshot.friendUids.associateWith { uid ->
            previousEvents[uid]
                ?.takeIf { it.revision > snapshot.revision }
                ?: FriendPresence(
                    status = if (uid in onlineUids) {
                        FriendPresenceStatus.ONLINE
                    } else {
                        FriendPresenceStatus.OFFLINE
                    },
                    revision = snapshot.revision,
                )
        }
        return FriendPresenceReducerState(
            serverEpoch = snapshot.serverEpoch,
            snapshotRevision = snapshot.revision,
            presenceByUid = nextPresence,
        )
    }

    fun reduce(event: PresencePayload): FriendPresenceReducerState {
        val incoming = event.toFriendPresence()
        if (event.serverEpoch != serverEpoch) {
            return FriendPresenceReducerState(
                serverEpoch = event.serverEpoch,
                pendingByUid = mapOf(event.uid to incoming),
            )
        }

        val baseline = snapshotRevision
        if (baseline == null) {
            return copy(
                pendingByUid = pendingByUid.withNewer(event.uid, incoming),
            )
        }
        if (incoming.revision <= baseline) {
            return this
        }

        val current = presenceByUid[event.uid]
        if (current != null) {
            if (incoming.revision <= current.revision) return this
            return copy(presenceByUid = presenceByUid + (event.uid to incoming))
        }

        // 联系人变更与其在线状态事件可能竞争。保持事件隐藏，直到完整快照确认成员身份；
        // 快照省略会丢弃它。
        return copy(pendingByUid = pendingByUid.withNewer(event.uid, incoming))
    }

    fun disconnected(): FriendPresenceReducerState = FriendPresenceReducerState()
}

private fun PresencePayload.toFriendPresence(): FriendPresence = FriendPresence(
    status = when (status) {
        PresencePayload.STATUS_ONLINE -> FriendPresenceStatus.ONLINE
        else -> FriendPresenceStatus.OFFLINE
    },
    lastSeenAt = lastSeenAt,
    revision = revision,
)

private fun Map<String, FriendPresence>.withNewer(
    uid: String,
    incoming: FriendPresence,
): Map<String, FriendPresence> {
    val current = this[uid]
    return if (current == null || incoming.revision > current.revision) {
        this + (uid to incoming)
    } else {
        this
    }
}
