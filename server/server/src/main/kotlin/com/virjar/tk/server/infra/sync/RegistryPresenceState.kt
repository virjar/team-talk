package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.domain.presence.PresenceTransition
import com.virjar.tk.protocol.model.FriendPresenceSnapshot
import com.virjar.tk.protocol.PresenceContractPolicy

/** presence 版本状态只被 ClientRegistry 的串行拥有者触碰。 */
internal class RegistryPresenceState(
    val serverEpoch: String,
    initialRevision: Long = 0L,
) {
    private var revision: Long = initialRevision

    init {
        PresenceContractPolicy.requireServerEpoch(serverEpoch)
        require(initialRevision >= 0L) { "Initial presence revision must be non-negative" }
    }

    fun onDeviceCountChanged(
        uid: String,
        previousDeviceCount: Int,
        currentDeviceCount: Int,
        occurredAt: () -> Long,
    ): PresenceTransition? {
        require(previousDeviceCount >= 0 && currentDeviceCount >= 0) {
            "Presence device counts must be non-negative"
        }
        val online = when {
            previousDeviceCount == 0 && currentDeviceCount > 0 -> true
            previousDeviceCount > 0 && currentDeviceCount == 0 -> false
            else -> return null
        }
        check(revision < Long.MAX_VALUE) { "Presence revision overflow" }
        val transition = PresenceTransition(
            uid = uid,
            online = online,
            occurredAt = occurredAt(),
            serverEpoch = serverEpoch,
            revision = revision + 1L,
        )
        revision = transition.revision
        return transition
    }

    fun snapshot(friendUids: List<String>, isOnline: (String) -> Boolean): FriendPresenceSnapshot =
        FriendPresenceSnapshot(
            serverEpoch = serverEpoch,
            revision = revision,
            friendUids = friendUids,
            onlineFriendUids = friendUids.filter(isOnline),
        )
}
