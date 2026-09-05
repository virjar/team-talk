package com.virjar.tk.server.domain.event

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyContracts
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.payload.NotifyPayload

/** 刻意没有持久化离线同步表示的事件的领域边界。 */
interface TransientEventPublisher {
    suspend fun emitTransient(uid: String, notifyType: NotifyType, payload: IProto)
}

/** 客户端恢复会话时 TCP 适配器使用的读侧。 */
interface SyncEventReader {
    /** 本读取器返回的每个游标背后的权威数据集的不透明身份。 */
    val datasetId: String

    fun getEventsAfter(uid: String, afterEventId: Long, limit: Int = 100): List<NotifyPayload>

    /**
     * 返回一个同时受条数与 wire 字节约束的重放页；或在持久化/推送所用的同一按用户闸门下，
     * 第二次空检查之后原子地激活实时投递。一个只能作为独立 NOTIFY 容纳的单条事件可以作为
     * 单元素页返回。
     */
    suspend fun nextBatchOrActivate(
        uid: String,
        sessionId: String,
        claimedDatasetId: String,
        afterEventId: Long,
        limit: Int,
        activate: suspend () -> Boolean,
    ): SyncBatchResult

    /** 释放这个已认证连接所持有的每个内存中重放/检查点租约。 */
    fun releaseSession(uid: String, sessionId: String)
}

sealed interface SyncBatchResult {
    data class Events(val events: List<NotifyPayload>) : SyncBatchResult
    data object Activated : SyncBatchResult
    data object ConnectionClosed : SyncBatchResult
    /** 该数值游标属于另一个服务器数据集，绝不能按值比较。 */
    data class DatasetMismatch(val datasetId: String) : SyncBatchResult
    /** 声称的持久化游标既不是零，也不是该用户拥有的事件。 */
    data object InvalidCursor : SyncBatchResult
}

/** 在服务器边界失败，而不是在错误的 NotifyType 下持久化载荷。 */
fun requireNotifyContract(notifyType: NotifyType, payload: IProto) {
    require(notifyType != NotifyType.EVENT_CURSOR_ADVANCED) {
        "Cursor advancement is a per-connection wire projection, not a business event"
    }
    val reader = NotifyContracts.payloads[notifyType] ?: return
    val expected = NotifyContracts.expectedPayloadClassName(notifyType, reader::class.java.name)
    val actual = payload::class.java.name
    require(expected == actual) {
        "Notify contract violation: $notifyType expects payload $expected but got $actual. " +
            "Fix the emit site or update NotifyContracts."
    }
}
