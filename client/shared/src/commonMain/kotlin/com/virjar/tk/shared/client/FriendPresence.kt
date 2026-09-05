package com.virjar.tk.shared.client

/** 客户端可以为一位好友安全呈现的在线状态。 */
enum class FriendPresenceStatus {
    /** 尚无快照为该好友建立权威状态。 */
    UNKNOWN,

    OFFLINE,
    ONLINE,
}

/**
 * 会话本地、非持久化的好友在线状态投影。
 *
 * 当服务器未提供时间戳（包括 ONLINE 与快照派生的 OFFLINE 状态）时 [lastSeenAt] 为零。[revision]
 * 是产生该值的服务器在线状态修订号；UNKNOWN 始终使用修订号零。
 */
data class FriendPresence(
    val status: FriendPresenceStatus,
    val lastSeenAt: Long = 0L,
    val revision: Long = 0L,
) {
    init {
        require(lastSeenAt >= 0L) { "lastSeenAt must be non-negative" }
        require(revision >= 0L) { "revision must be non-negative" }
        if (status == FriendPresenceStatus.UNKNOWN) {
            require(lastSeenAt == 0L && revision == 0L) {
                "UNKNOWN presence cannot carry server state"
            }
        }
        if (status == FriendPresenceStatus.ONLINE) {
            require(lastSeenAt == 0L) { "ONLINE presence must use lastSeenAt=0" }
        }
    }

    companion object {
        val Unknown = FriendPresence(FriendPresenceStatus.UNKNOWN)
    }
}
