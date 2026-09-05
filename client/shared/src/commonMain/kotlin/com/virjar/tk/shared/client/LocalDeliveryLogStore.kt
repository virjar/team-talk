package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.ProtoCodec

/** 公开投递历史读取在构造任何 SQL 查询之前就被限界。 */
const val MAX_BOT_DELIVERY_HISTORY_PAGE_SIZE = 1_000

/** ACK 历史是诊断/重放便利；待处理行是独立的可靠事实。 */
internal const val MAX_ACKED_BOT_DELIVERY_HISTORY = 1_024
internal const val MAX_ACKED_BOT_DELIVERY_HISTORY_PAYLOAD_BYTES = 32L * 1_024L * 1_024L

/** 正的投递游标不再标识一个完整的保留历史续读。 */
class BotDeliveryHistoryCursorExpiredException(
    val afterEventId: Long,
    val retainedFloorEventId: Long,
) : IllegalArgumentException("Bot delivery history cursor is older than the retained window")

internal data class BotDeliveryLogLimits(
    val historyPageSize: Int = MAX_BOT_DELIVERY_HISTORY_PAGE_SIZE,
    val ackedHistoryCount: Int = MAX_ACKED_BOT_DELIVERY_HISTORY,
    val ackedHistoryPayloadBytes: Long = MAX_ACKED_BOT_DELIVERY_HISTORY_PAYLOAD_BYTES,
) {
    init {
        // 测试接缝可以收紧生产限制，但绝不能放宽它们。
        require(historyPageSize in 1..MAX_BOT_DELIVERY_HISTORY_PAGE_SIZE)
        require(ackedHistoryCount in 1..MAX_ACKED_BOT_DELIVERY_HISTORY)
        require(ackedHistoryPayloadBytes in 1L..MAX_ACKED_BOT_DELIVERY_HISTORY_PAYLOAD_BYTES)
    }
}

private val DEFAULT_BOT_DELIVERY_LOG_LIMITS = BotDeliveryLogLimits()

/** 一个 LocalCache 会话拥有的持久同步游标与无头投递日志。 */
internal class LocalDeliveryLogStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val limits: BotDeliveryLogLimits = DEFAULT_BOT_DELIVERY_LOG_LIMITS,
) {
    init {
        // 预绑定的缓存可能包含更旧构建写入的历史。在 owner 发布之前收敛它；该查询本身至多读取
        // count+1 行小元数据行。
        synchronized(stateLock) {
            queries.transaction {
                queries.ensureBotInboxMetadata()
                pruneAckedBotMessageHistoryLocked()
            }
        }
    }

    fun getSyncState(): ServerProjectionSyncState? = cacheUseGate.use {
        synchronized(stateLock) { selectSyncStateLocked() }
    }

    fun bindSyncDataset(datasetId: String): ServerProjectionSyncState = cacheUseGate.use {
        com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
        synchronized(stateLock) {
            lateinit var boundState: ServerProjectionSyncState
            queries.transaction {
                queries.bindSyncDataset(datasetId)
                val state = checkNotNull(selectSyncStateLocked()) { "sync state was not bound" }
                check(state.datasetId == datasetId) {
                    "LocalCache belongs to a different server dataset"
                }
                boundState = state
            }
            boundState
        }
    }

    fun advanceSyncCursor(expectedDatasetId: String, eventId: Long): ServerProjectionSyncState =
        cacheUseGate.use {
            com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(expectedDatasetId)
            require(eventId > 0L) { "eventId must be positive" }
            synchronized(stateLock) {
                lateinit var advancedState: ServerProjectionSyncState
                queries.transaction {
                    queries.advanceSyncCursor(
                        eventId = eventId,
                        expectedDatasetId = expectedDatasetId,
                    )
                    val persisted = checkNotNull(selectSyncStateLocked()) { "sync state is not bound" }
                    check(persisted.datasetId == expectedDatasetId) {
                        "A retired dataset cannot advance the current sync cursor"
                    }
                    check(persisted.cursor >= eventId) { "sync cursor did not advance" }
                    advancedState = persisted
                }
                advancedState
            }
        }

    fun enqueueBotMessage(eventId: Long, message: Message) {
        cacheUseGate.use {
            require(eventId > 0L) { "eventId must be positive" }
            require(message.serverSeq > 0L) { "durable bot messages require a positive serverSeq" }
            synchronized(stateLock) {
                queries.transaction {
                    // 压缩会移除 ACK 行，因此其单调下限也保持为每个已退役 event id 的持久
                    // 重放幂等墓碑。
                    if (eventId > retainedBotMessageHistoryFloorLocked()) {
                        queries.enqueueBotMessage(
                            eventId,
                            message.chatId,
                            message.serverSeq,
                            ProtoCodec.encode(message),
                            System.currentTimeMillis(),
                        )
                    }
                }
            }
        }
    }

    fun peekBotMessage(): PendingBotMessage? = cacheUseGate.use {
        synchronized(stateLock) {
            queries.peekBotMessage().executeAsOneOrNull()?.let { row ->
                PendingBotMessage(
                    eventId = row.event_id,
                    message = ProtoCodec.decode(Message, row.payload),
                )
            }
        }
    }

    fun ackBotMessage(eventId: Long, now: Long) {
        cacheUseGate.use {
            require(eventId > 0L) { "eventId must be positive" }
            synchronized(stateLock) {
                queries.transaction {
                    queries.ackBotMessage(now, eventId)
                    pruneAckedBotMessageHistoryLocked()
                }
            }
        }
    }

    fun listBotMessageDeliveries(
        afterEventId: Long,
        chatId: String?,
        limit: Int,
    ): List<PendingBotMessage> = cacheUseGate.use {
        require(afterEventId >= 0L) { "afterEventId must be non-negative" }
        require(limit in 1..limits.historyPageSize) {
            "limit must be between 1 and ${limits.historyPageSize}"
        }
        synchronized(stateLock) {
            val retainedFloor = retainedBotMessageHistoryFloorLocked()
            if (afterEventId != 0L && afterEventId < retainedFloor) {
                throw BotDeliveryHistoryCursorExpiredException(afterEventId, retainedFloor)
            }
            queries.selectBotMessageDeliveries(afterEventId, chatId, limit.toLong())
                .executeAsList()
                .map { row ->
                    PendingBotMessage(row.event_id, ProtoCodec.decode(Message, row.payload))
                }
        }
    }

    fun maxBotMessageEventId(): Long = cacheUseGate.use {
        synchronized(stateLock) {
            maxOf(
                queries.selectMaxBotMessageEventId().executeAsOne(),
                retainedBotMessageHistoryFloorLocked(),
            )
        }
    }

    /** 调用方持有 [stateLock] 与一个外层 SQL 事务。 */
    private fun pruneAckedBotMessageHistoryLocked() {
        val newest = queries.selectAckedBotMessageStorageNewestFirst(
            (limits.ackedHistoryCount + 1).toLong(),
        ).executeAsList()
        var retainedPayloadBytes = 0L
        var pruneThroughEventId: Long? = null
        for (index in newest.indices) {
            val row = newest[index]
            val rowPayloadBytes = checkNotNull(row.payload_bytes) {
                "ACK bot delivery payload size is unexpectedly null"
            }
            check(rowPayloadBytes >= 0L) { "ACK bot delivery payload size is negative" }
            val fitsCount = index < limits.ackedHistoryCount
            val fitsBytes = rowPayloadBytes <= limits.ackedHistoryPayloadBytes - retainedPayloadBytes
            if (fitsCount && fitsBytes) {
                retainedPayloadBytes += rowPayloadBytes
            } else {
                pruneThroughEventId = row.event_id
                break
            }
        }
        val desiredBoundary = pruneThroughEventId ?: return
        // 如果压缩在一条更旧的待处理行之上打了一个洞，单个 Long 游标无法区分新保留窗口页与
        // 过期页。绝不把下限推进到第一条未确认投递之前。普通单消费者 ACK 是有序的，因此该守卫
        // 只为遗留/损坏或刻意乱序的 ACK 状态固定清理。
        val oldestPending = queries.selectOldestPendingBotMessageEventId().executeAsOne()
        val safeCeiling = if (oldestPending > 0L) {
            minOf(desiredBoundary, oldestPending - 1L)
        } else {
            desiredBoundary
        }
        if (safeCeiling <= 0L) return
        val boundary = queries.selectNewestAckedBotMessageEventIdThrough(safeCeiling).executeAsOne()
        if (boundary <= 0L) return
        queries.ensureBotInboxMetadata()
        queries.advanceBotInboxRetainedFloor(boundary)
        queries.deleteAckedBotMessagesThrough(boundary)
    }

    private fun retainedBotMessageHistoryFloorLocked(): Long {
        queries.ensureBotInboxMetadata()
        return queries.selectBotInboxRetainedFloor().executeAsOne()
    }

    private fun selectSyncStateLocked(): ServerProjectionSyncState? =
        queries.selectSyncState().executeAsOneOrNull()?.let { row ->
            ServerProjectionSyncState(row.dataset_id, row.cursor)
        }
}
