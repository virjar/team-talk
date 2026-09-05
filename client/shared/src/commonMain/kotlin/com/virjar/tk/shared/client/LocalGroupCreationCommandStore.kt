package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries

/** 一个 deployment/account LocalCache 拥有的单槽持久 GUI 命令存储。 */
internal class LocalGroupCreationCommandStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
) {
    // SQL/打开失败仍然使 LocalCache 构造失败。只有结构可读但不规范的命令才被隔离：因为一个可靠
    // 槽损坏而丢失每个离线投影会违反缓存的可用性边界。
    private var state: GroupCreationCommandStoreState =
        queries.selectPendingGroupCreation().executeAsOneOrNull()?.let { row ->
            try {
                GroupCreationCommandStoreState.Healthy(
                    PendingGroupCreationCommand.restore(
                        operationId = row.operation_id,
                        creatorUid = row.creator_uid,
                        name = row.name,
                        avatar = row.avatar,
                        encodedMemberUids = row.member_uids,
                    ),
                )
            } catch (corrupt: IllegalStateException) {
                GroupCreationCommandStoreState.Poisoned(
                    CorruptPendingGroupCreationException(corrupt),
                )
            }
        } ?: GroupCreationCommandStoreState.Healthy(null)

    fun get(): PendingGroupCreationCommand? = cacheUseGate.use {
        synchronized(stateLock) { healthyStateLocked().pending }
    }

    /**
     * 原子替换唯一的 GUI 草稿。内存值只有在 SQLite 接受完整规范命令之后才推进，这是 RPC 之前的
     * 持久化屏障。
     */
    fun replace(command: PendingGroupCreationCommand) = cacheUseGate.use {
        synchronized(stateLock) {
            healthyStateLocked()
            val canonical = command.requireCanonical()
            queries.upsertPendingGroupCreation(
                canonical.operationId,
                canonical.creatorUid,
                canonical.name,
                canonical.avatar,
                canonical.encodedMemberUids(),
            )
            state = GroupCreationCommandStoreState.Healthy(canonical)
        }
    }

    /** 更旧已替换命令的迟到成功不能清除当前草稿。 */
    fun clearIfOperation(operationId: String): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val current = healthyStateLocked().pending ?: return@synchronized false
            if (current.operationId != operationId) return@synchronized false
            queries.deletePendingGroupCreation(operationId)
            state = GroupCreationCommandStoreState.Healthy(null)
            true
        }
    }

    private fun healthyStateLocked(): GroupCreationCommandStoreState.Healthy = when (val current = state) {
        is GroupCreationCommandStoreState.Healthy -> current
        is GroupCreationCommandStoreState.Poisoned -> throw current.failure
    }
}

/** 未知命令被保留用于账号数据清理，而不是被静默替换。 */
internal class CorruptPendingGroupCreationException(cause: Throwable) : IllegalStateException(
    "本地待创建群组记录损坏，已禁止覆盖该未知操作",
    cause,
)

private sealed interface GroupCreationCommandStoreState {
    data class Healthy(val pending: PendingGroupCreationCommand?) : GroupCreationCommandStoreState
    data class Poisoned(val failure: CorruptPendingGroupCreationException) : GroupCreationCommandStoreState
}
