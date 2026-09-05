package com.virjar.tk.shared.client

/**
 * 一个按 key 的 RPC 投影请求。缓存实例与投影类型由 [owner] 编码；调用方不能把 user 租约应用
 * 到 chat/member 状态，也不能跨缓存实例应用。
 */
class ProjectionSnapshotLease internal constructor(
    internal val owner: Any,
    internal val epoch: Long,
    internal val requestId: Long,
    internal val key: String,
)

/**
 * 面向模块外 [LocalCache] 实现的 latest-request-wins 能力。
 *
 * 门禁是唯一能签发或校验其租约的对象。Owner 身份、epoch 与请求 id 密封在 `shared` 内部；实现
 * 只能开始、消费、放弃、失效或 reset 一个请求。调用方拥有同步，因此校验与匹配的缓存写入保持原子。
 */
class KeyedProjectionSnapshotGate(
    private val label: String,
) {
    private val owner = Any()
    private var epoch = 0L
    private var nextRequestId = 0L
    private val currentRequests = mutableMapOf<String, Long>()

    fun begin(key: String): ProjectionSnapshotLease {
        require(key.isNotBlank()) { "$label key must not be blank" }
        nextRequestId = next(nextRequestId, "$label request id")
        currentRequests[key] = nextRequestId
        return ProjectionSnapshotLease(owner, epoch, nextRequestId, key)
    }

    fun isCurrent(lease: ProjectionSnapshotLease): Boolean = isCurrent(lease, lease.key)

    /** 当一个精确 key 请求仍可以安装其响应时为 true。 */
    internal fun hasCurrentRequest(key: String): Boolean = currentRequests.containsKey(key)

    fun consumeIfCurrent(lease: ProjectionSnapshotLease, key: String): Boolean {
        if (!isCurrent(lease, key)) return false
        currentRequests.remove(key)
        return true
    }

    /** 只释放该仍然当前的精确请求；一个更旧的 finally 不能清除其后继者。 */
    fun abandon(lease: ProjectionSnapshotLease): Boolean {
        if (!isCurrent(lease, lease.key)) return false
        currentRequests.remove(lease.key)
        return true
    }

    fun invalidate(key: String) {
        currentRequests.remove(key)
    }

    fun reset() {
        epoch = next(epoch, "$label epoch")
        currentRequests.clear()
    }

    private fun isCurrent(lease: ProjectionSnapshotLease, key: String): Boolean =
        lease.owner === owner &&
            lease.epoch == epoch &&
            lease.key == key &&
            currentRequests[key] == lease.requestId

    private fun next(current: Long, counter: String): Long {
        check(current < Long.MAX_VALUE) { "$counter exhausted" }
        return current + 1L
    }
}
