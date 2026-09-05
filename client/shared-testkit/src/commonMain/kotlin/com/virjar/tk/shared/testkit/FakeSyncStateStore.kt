package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.ServerProjectionSyncState
import com.virjar.tk.protocol.payload.SyncDatasetIdPolicy

/** 不可分割的数据集标识与持久化事件游标这一测试事实的内存所有者。 */
internal class FakeSyncStateStore(initialDatasetId: String?) {
    private val lock = Any()
    private var state: ServerProjectionSyncState? = initialDatasetId?.let {
        ServerProjectionSyncState(it, 0L)
    }

    fun get(): ServerProjectionSyncState? = synchronized(lock) { state }

    fun bind(datasetId: String): ServerProjectionSyncState {
        SyncDatasetIdPolicy.requireValid(datasetId)
        return synchronized(lock) {
            val current = state
            if (current == null) {
                ServerProjectionSyncState(datasetId, 0L).also { state = it }
            } else {
                check(current.datasetId == datasetId) {
                    "FakeLocalCache belongs to a different server dataset"
                }
                current
            }
        }
    }

    fun advance(expectedDatasetId: String, eventId: Long): ServerProjectionSyncState {
        SyncDatasetIdPolicy.requireValid(expectedDatasetId)
        require(eventId > 0L) { "eventId must be positive" }
        return synchronized(lock) {
            val current = checkNotNull(state) { "FakeLocalCache is not bound to a server dataset" }
            check(current.datasetId == expectedDatasetId) {
                "A retired dataset cannot advance the current sync cursor"
            }
            ServerProjectionSyncState(
                datasetId = current.datasetId,
                cursor = maxOf(current.cursor, eventId),
            ).also { state = it }
        }
    }

    /** 在测试替身的完整检查点替换编排期间持有权威锁。 */
    fun applyCheckpoint(
        expectedDatasetId: String,
        expectedCursor: Long,
        baseEventId: Long,
        apply: () -> Unit,
    ): ServerProjectionSyncState {
        SyncDatasetIdPolicy.requireValid(expectedDatasetId)
        require(expectedCursor >= 0L) { "expectedCursor must be non-negative" }
        require(baseEventId >= 0L) { "baseEventId must be non-negative" }
        return synchronized(lock) {
            val current = checkNotNull(state) { "FakeLocalCache is not bound to a server dataset" }
            check(current.datasetId == expectedDatasetId && current.cursor == expectedCursor) {
                "checkpoint no longer matches the current sync authority"
            }
            apply()
            ServerProjectionSyncState(expectedDatasetId, baseEventId).also { state = it }
        }
    }

    /** 在测试替身的完整跨投影重置编排期间持有游标锁。 */
    fun resetProjection(datasetId: String, reset: () -> Unit) {
        SyncDatasetIdPolicy.requireValid(datasetId)
        synchronized(lock) {
            reset()
            state = ServerProjectionSyncState(datasetId, 0L)
        }
    }
}
