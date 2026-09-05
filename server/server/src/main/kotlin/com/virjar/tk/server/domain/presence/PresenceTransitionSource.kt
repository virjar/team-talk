package com.virjar.tk.server.domain.presence

import com.virjar.tk.protocol.model.FriendPresenceSnapshot
import com.virjar.tk.protocol.PresenceContractPolicy

/** 实时会话责任者在状态变化线性化点捕获的不可变转换。 */
data class PresenceTransition(
    val uid: String,
    val online: Boolean,
    val occurredAt: Long,
    val serverEpoch: String,
    val revision: Long,
) {
    init {
        PresenceContractPolicy.requireUid(uid, "presence transition uid")
        require(occurredAt >= 0L) { "Presence transition time must be non-negative" }
        PresenceContractPolicy.requireServerEpoch(serverEpoch)
        require(revision > 0L) { "Presence transition revision must be positive" }
    }
}

/** 仅在用户首次上线与最后离线转换时调用的非阻塞观察者。 */
fun interface PresenceTransitionObserver {
    fun onTransition(transition: PresenceTransition)
}

/** 比较并卸载租约；一个过期的责任者绝不能卸载后来的替代观察者。 */
fun interface PresenceObserverLease {
    fun uninstall()
}

/** 由实时连接注册表实现的单观察者连接生命周期端口。 */
interface PresenceTransitionSource {
    fun installPresenceObserver(observer: PresenceTransitionObserver): PresenceObserverLease
}

/**
 * 有界的好友在线状态读取端口。实现必须在一条责任者命令中线性化修订与在线成员关系，
 * 并且绝不能暴露其进程全局的在线 uid 集合。
 */
fun interface FriendPresenceSnapshotReader {
    suspend fun snapshot(friendUids: Set<String>): FriendPresenceSnapshot
}
