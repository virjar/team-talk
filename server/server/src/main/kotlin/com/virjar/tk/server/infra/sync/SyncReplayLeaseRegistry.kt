package com.virjar.tk.server.infra.sync

/**
 * 已鉴权连接仍然需要的持久游标的进程本地保护。
 *
 * 租约刻意与连接绑定：新 checkpoint 替换该连接先前的
 * 重放位置，普通尾部确认只把受保护游标向前移动，
 * 断开连接则移除条目。PostgreSQL 仍是流的事实来源；此注册表
 * 只是事件压缩器的协调输入，压缩器在持有同一每用户投递门闩
 * 的情况下使用 [minimumProtectedCursor]。
 */
class SyncReplayLeaseRegistry {
    private val leases = mutableMapOf<SessionKey, Lease>()

    /** 确保普通重放在其受守卫的 PostgreSQL 读取开始之前拥有连接槽。 */
    @Synchronized
    fun reserveReplay(uid: String, sessionId: String) {
        requireIdentity(uid, sessionId)
        leases.putIfAbsent(
            SessionKey(uid, sessionId),
            Lease(checkpointId = null, protectedCursor = null),
        )
    }

    /** 在可能阻塞的 checkpoint 锚定读取之前，先预留连接槽。 */
    @Synchronized
    fun reserveCheckpoint(uid: String, sessionId: String, checkpointId: String) {
        requireIdentity(uid, sessionId)
        require(checkpointId.isNotBlank()) { "checkpointId must not be blank" }
        leases[SessionKey(uid, sessionId)] = Lease(
            checkpointId = checkpointId,
            protectedCursor = null,
        )
    }

    /**
     * 只在预留存续时才发布锚定 floor。断开连接会移除
     * 预留，因此一个在连接取消之后才返回的 JDBC 调用无法复活
     * 孤儿租约。
     */
    @Synchronized
    fun publishCheckpoint(
        uid: String,
        sessionId: String,
        checkpointId: String,
        baseEventId: Long,
    ): Boolean {
        requireIdentity(uid, sessionId)
        require(checkpointId.isNotBlank()) { "checkpointId must not be blank" }
        require(baseEventId >= 0L) { "baseEventId must be non-negative" }
        val key = SessionKey(uid, sessionId)
        val reservation = leases[key]
        if (reservation?.checkpointId != checkpointId || reservation.protectedCursor != null) return false
        leases[key] = reservation.copy(protectedCursor = baseEventId)
        return true
    }

    @Synchronized
    fun requireCheckpoint(uid: String, sessionId: String, checkpointId: String) {
        requireIdentity(uid, sessionId)
        require(checkpointId.isNotBlank()) { "checkpointId must not be blank" }
        val lease = leases[SessionKey(uid, sessionId)]
        require(lease?.checkpointId == checkpointId && lease.protectedCursor != null) {
            "同步 checkpoint 已失效，请重新开始同步"
        }
    }

    /** 记录客户端已确认的尾部游标，而不丢弃其 checkpoint 身份。 */
    @Synchronized
    fun advanceReplay(uid: String, sessionId: String, acknowledgedEventId: Long): Boolean {
        requireIdentity(uid, sessionId)
        require(acknowledgedEventId >= 0L) { "acknowledgedEventId must be non-negative" }
        val key = SessionKey(uid, sessionId)
        val existing = leases[key] ?: return false
        leases[key] = when {
            existing.protectedCursor == null && existing.checkpointId == null ->
                existing.copy(protectedCursor = acknowledgedEventId)
            existing.protectedCursor == null -> error("checkpoint anchor is not published")
            acknowledgedEventId <= existing.protectedCursor -> existing
            else -> existing.copy(protectedCursor = acknowledgedEventId)
        }
        return true
    }

    @Synchronized
    fun release(uid: String, sessionId: String) {
        if (uid.isBlank() || sessionId.isBlank()) return
        leases.remove(SessionKey(uid, sessionId))
    }

    /** 压缩器对此用户绝不能越过的最低已确认游标。 */
    @Synchronized
    fun minimumProtectedCursor(uid: String): Long? = leases.asSequence()
        .filter { (key, _) -> key.uid == uid }
        .mapNotNull { (_, lease) -> lease.protectedCursor }
        .minOrNull()

    @Synchronized
    internal fun leaseFor(uid: String, sessionId: String): LeaseSnapshot? =
        leases[SessionKey(uid, sessionId)]?.let { lease ->
            LeaseSnapshot(lease.checkpointId, lease.protectedCursor)
        }

    private fun requireIdentity(uid: String, sessionId: String) {
        require(uid.isNotBlank()) { "uid must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    private data class SessionKey(val uid: String, val sessionId: String)

    private data class Lease(
        val checkpointId: String?,
        val protectedCursor: Long?,
    )

    internal data class LeaseSnapshot(
        val checkpointId: String?,
        val protectedCursor: Long?,
    )
}
