package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.domain.event.TransientEventPublisher
import com.virjar.tk.server.domain.event.SyncBatchResult
import com.virjar.tk.server.domain.event.SyncEventReader
import com.virjar.tk.server.domain.event.requireNotifyContract
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.SyncStreams
import com.virjar.tk.server.infra.db.execRawSql
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet

data class SyncEventCleanupResult(
    val deletedEvents: Int,
    val backlogMayRemain: Boolean,
)

data class SyncEventRetentionConfig(
    val retentionMillis: Long = DEFAULT_RETENTION_DAYS * MILLIS_PER_DAY,
    val maxUsersPerRun: Int = DEFAULT_MAX_USERS_PER_RUN,
    val maxEventsPerUser: Int = DEFAULT_MAX_EVENTS_PER_USER,
) {
    init {
        require(retentionMillis in 1..MAX_RETENTION_MILLIS) {
            "sync event retention is out of range"
        }
        require(maxUsersPerRun in 1..MAX_USERS_PER_RUN) {
            "sync event cleanup user batch is out of range"
        }
        require(maxEventsPerUser in 1..MAX_EVENTS_PER_USER) {
            "sync event cleanup event batch is out of range"
        }
    }

    companion object {
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
        private const val DEFAULT_RETENTION_DAYS = 30L
        private const val MAX_RETENTION_DAYS = 3_650L
        private const val MAX_RETENTION_MILLIS = MAX_RETENTION_DAYS * MILLIS_PER_DAY
        private const val DEFAULT_MAX_USERS_PER_RUN = 64
        private const val MAX_USERS_PER_RUN = 1_024
        private const val DEFAULT_MAX_EVENTS_PER_USER = 512
        private const val MAX_EVENTS_PER_USER = 4_096

        fun fromEnvironment(environment: (String) -> String? = System::getenv): SyncEventRetentionConfig {
            val configured = environment(RETENTION_DAYS_ENV)
            val days = configured?.toLongOrNull() ?: if (configured == null) {
                DEFAULT_RETENTION_DAYS
            } else {
                throw IllegalArgumentException("$RETENTION_DAYS_ENV must be an integer")
            }
            require(days in 1..MAX_RETENTION_DAYS) {
                "$RETENTION_DAYS_ENV must be in 1..$MAX_RETENTION_DAYS"
            }
            return SyncEventRetentionConfig(retentionMillis = Math.multiplyExact(days, MILLIS_PER_DAY))
        }

        internal const val RETENTION_DAYS_ENV = "TEAMTALK_SYNC_EVENT_RETENTION_DAYS"
    }
}

fun interface SyncEventReadHooks {
    suspend fun afterFirstEmpty(uid: String, afterEventId: Long)

    object None : SyncEventReadHooks {
        override suspend fun afterFirstEmpty(uid: String, afterEventId: Long) = Unit
    }
}

/**
 * 读取用户事件流，处理重连补发、转入实时接收以及历史事件清理；也提供瞬时事件发送入口。
 * 持久事件由 ExposedPgUnitOfWork 与业务数据一起提交，在线投递由 [SyncEventDispatcher] 负责。
 * 本类不负责持久事件的创建。
 */
class SyncEventService(
    private val database: Database,
    private val dispatcher: SyncEventDispatcher,
    override val datasetId: String,
    private val leases: SyncReplayLeaseRegistry = SyncReplayLeaseRegistry(),
    private val readHooks: SyncEventReadHooks = SyncEventReadHooks.None,
    private val retention: SyncEventRetentionConfig = SyncEventRetentionConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) : TransientEventPublisher, SyncEventReader {
    init {
        com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
    }

    private val logger = LoggerFactory.getLogger("SyncEventService")
    private val cleanupMutex = Mutex()
    /** 由 [cleanupMutex] 守卫的 keyset 游标，防止被租约钉住的低 UID 饿死之后的流。 */
    private var cleanupAfterUid: String? = null

    override suspend fun emitTransient(uid: String, notifyType: NotifyType, payload: IProto) {
        requireNotifyContract(notifyType, payload)
        try {
            dispatcher.deliverTransient(uid, NotifyPayload(0, notifyType.code, ProtoCodec.encode(payload)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn("Failed to push transient event to uid={}", uid, error)
        }
    }

    /**
     * 查询用户在某个 eventId 之后的所有事件（离线补发）。
     *
     * 这是测试和诊断用的单页读取入口；生产分页必须走带 session lease 的
     * [nextBatchOrActivate]。低于持久压缩 floor 的游标会明确失败，不能静默返回空页。
     */
    override fun getEventsAfter(uid: String, afterEventId: Long, limit: Int): List<NotifyPayload> {
        require(afterEventId >= 0L) { "afterEventId must be non-negative" }
        require(limit > 0) { "limit must be positive" }
        val page = readOwnedPage(uid, afterEventId, limit)
        require(page != null) { "同步游标已失效" }
        return page
    }

    /**
     * 每个 replay page 都在 per-user delivery gate 内校验、读取并更新 lease：
     *
     * - compactor 使用同一门闩时不能跨过刚读出的分页游标；
     * - 事件先提交：空页后的二次查询必然看见；
     * - 激活先持有门闩：dispatcher 必须等待 SYNC_READY/注册完成，后续 live NOTIFY
     *   只能排在 READY 后。
     */
    override suspend fun nextBatchOrActivate(
        uid: String,
        sessionId: String,
        claimedDatasetId: String,
        afterEventId: Long,
        limit: Int,
        activate: suspend () -> Boolean,
    ): SyncBatchResult {
        require(limit in 1..MAX_QUERY_EVENTS) { "sync limit must be in 1..$MAX_QUERY_EVENTS" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        if (claimedDatasetId != datasetId) {
            leases.release(uid, sessionId)
            return SyncBatchResult.DatasetMismatch(datasetId)
        }
        // floor 检查、返回页与重放租约更新共享压缩器的门闩。因此压缩器
        // 无法在读取有效页与发布连接受保护游标之间
        // 的窄窗口内推进 floor。
        return dispatcher.withDeliveryGate(uid) {
            leases.reserveReplay(uid, sessionId)
            val first = readOwnedPage(uid, afterEventId, limit)
            if (first == null) {
                leases.release(uid, sessionId)
                return@withDeliveryGate SyncBatchResult.InvalidCursor
            }
            if (!leases.advanceReplay(uid, sessionId, afterEventId)) {
                return@withDeliveryGate SyncBatchResult.ConnectionClosed
            }
            if (first.isNotEmpty()) {
                return@withDeliveryGate SyncBatchResult.Events(wireBoundedPage(first))
            }
            readHooks.afterFirstEmpty(uid, afterEventId)
            val second = readOwnedPage(uid, afterEventId, limit)
            when {
                second == null -> {
                    leases.release(uid, sessionId)
                    SyncBatchResult.InvalidCursor
                }
                second.isNotEmpty() -> {
                    SyncBatchResult.Events(wireBoundedPage(second))
                }
                activate() -> {
                    leases.release(uid, sessionId)
                    SyncBatchResult.Activated
                }
                else -> {
                    leases.release(uid, sessionId)
                    SyncBatchResult.ConnectionClosed
                }
            }
        }
    }

    override fun releaseSession(uid: String, sessionId: String) {
        leases.release(uid, sessionId)
    }

    /** Null 表示游标低于压缩 floor，或超出此用户的流头部。 */
    private fun readOwnedPage(uid: String, afterEventId: Long, limit: Int): List<NotifyPayload>? {
        if (afterEventId < 0L) return null
        return transaction(
            transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
            readOnly = true,
            db = database,
        ) {
            val stream = SyncStreams.selectAll()
                .where { SyncStreams.uid eq uid }
                .limit(1)
                .singleOrNull()
            if (stream == null) {
                return@transaction if (afterEventId == 0L) emptyList() else null
            }
            val compactedThrough = stream[SyncStreams.compactedThrough]
            val lastSeq = stream[SyncStreams.lastSeq]
            if (afterEventId < compactedThrough || afterEventId > lastSeq) {
                return@transaction null
            }
            SyncEvents.selectAll()
                .where { (SyncEvents.uid eq uid) and (SyncEvents.streamSeq greater afterEventId) }
                .orderBy(SyncEvents.streamSeq)
                .limit(limit)
                .map { row ->
                    NotifyPayload(
                        eventId = row[SyncEvents.streamSeq],
                        notifyType = row[SyncEvents.eventType],
                        payload = row[SyncEvents.payload],
                    )
                }
        }
    }

    /**
     * 页面同时受事件数与编码数据包字节数的界定。若只是批次计数字节
     * 阻止第一个原本合法的事件装入，就返回那单个事件，使 TCP
     * 适配器能在同步期间把它作为独立的持久 NOTIFY 发送。
     */
    private fun wireBoundedPage(events: List<NotifyPayload>): List<NotifyPayload> {
        val prefix = SyncBatchPayload.boundedPrefix(events)
        return if (prefix.isNotEmpty()) prefix else listOf(events.first())
    }

    /**
     * 只物理移除已经分发、已过期、且已被当前
     * 权威投影表示的前缀。每个用户的压缩与重放及 checkpoint 锚定
     * 位于同一投递门闩之后；删除 + 持久 floor 推进共享一个数据库事务。
     */
    suspend fun cleanupExpiredEvents(): SyncEventCleanupResult = cleanupMutex.withLock {
        val now = clock()
        require(now >= 0L) { "sync event cleanup clock must be non-negative" }
        val cutoff = if (now <= retention.retentionMillis) 0L else now - retention.retentionMillis
        val candidates = findCleanupCandidates(
            cutoff = cutoff,
            afterUid = cleanupAfterUid,
            limit = retention.maxUsersPerRun + 1,
        )
        val selected = candidates.take(retention.maxUsersPerRun)
        cleanupAfterUid = if (candidates.size > retention.maxUsersPerRun) {
            selected.last()
        } else {
            null
        }
        var deletedEvents = 0
        var boundedPrefixBacklog = false
        selected.forEach { uid ->
            val result = compactUserPrefix(uid, cutoff)
            deletedEvents += result.deletedEvents
            boundedPrefixBacklog = boundedPrefixBacklog || result.backlogMayRemain
        }
        // keyset 游标已经越过此页，因此只要还有未访问的页面存在，
        // 即使每个选中的流当前都被租约保护，也要立即继续。
        // 最后一页清除游标，自然回到正常的小时节奏。
        val unvisitedCandidatesMayRemain = candidates.size > retention.maxUsersPerRun
        SyncEventCleanupResult(
            deletedEvents = deletedEvents,
            backlogMayRemain = boundedPrefixBacklog || unvisitedCandidatesMayRemain,
        )
    }

    /**
     * 候选选择只检查持久 floor 之后恰好那一行。LEFT JOIN 还会
     * 暴露不可能的缺失头部，使压缩大声失败而不是猜测 floor。
     */
    private suspend fun findCleanupCandidates(
        cutoff: Long,
        afterUid: String?,
        limit: Int,
    ): List<String> =
        newSuspendedTransaction(
            context = Dispatchers.IO,
            db = database,
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
            readOnly = true,
        ) {
            maxAttempts = 1
            val cursorPredicate = if (afterUid == null) "" else "AND stream_row.uid > ?::varchar"
            val args = buildList {
                if (afterUid != null) add(SyncStreams.uid.columnType to afterUid)
                add(SyncEvents.createdAt.columnType to cutoff)
                add(SyncEvents.eventType.columnType to limit)
            }
            execRawSql(
                stmt = """
                    SELECT stream_row.uid
                    FROM sync_streams stream_row
                    LEFT JOIN sync_events head_event
                      ON head_event.uid = stream_row.uid
                     AND head_event.stream_seq = stream_row.compacted_through + 1
                    WHERE stream_row.compacted_through < stream_row.last_seq
                      $cursorPredicate
                      AND (
                        head_event.uid IS NULL
                        OR (
                          head_event.dispatched_at IS NOT NULL
                          AND head_event.created_at <= ?::bigint
                        )
                      )
                    ORDER BY stream_row.uid
                    LIMIT ?::integer
                """.trimIndent(),
                args = args,
                explicitStatementType = StatementType.SELECT,
            ) { resultSet: ResultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getString("uid"))
                }
            } ?: error("sync event cleanup candidate query returned no result set")
        }

    private suspend fun compactUserPrefix(uid: String, cutoff: Long): UserCompactionResult =
        dispatcher.withDeliveryGate(uid) {
            val protectedCursor = leases.minimumProtectedCursor(uid)
            newSuspendedTransaction(
                context = Dispatchers.IO,
                db = database,
                transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
            ) {
                maxAttempts = 1
                val stream = SyncStreams.selectAll()
                    .where { SyncStreams.uid eq uid }
                    .forUpdate()
                    .limit(1)
                    .singleOrNull()
                    ?: return@newSuspendedTransaction UserCompactionResult.None
                val oldFloor = stream[SyncStreams.compactedThrough]
                val lastSeq = stream[SyncStreams.lastSeq]
                val upperBound = minOf(lastSeq, protectedCursor ?: lastSeq)
                if (upperBound <= oldFloor) {
                    return@newSuspendedTransaction UserCompactionResult.None
                }

                val rows = SyncEvents.selectAll()
                    .where {
                        (SyncEvents.uid eq uid) and
                            (SyncEvents.streamSeq greater oldFloor) and
                            (SyncEvents.streamSeq lessEq upperBound)
                    }
                    .orderBy(SyncEvents.streamSeq)
                    .limit(retention.maxEventsPerUser)
                    .toList()
                check(rows.isNotEmpty()) {
                    "sync event stream $uid is missing row ${oldFloor + 1L} above its compacted floor"
                }

                var expectedSeq = oldFloor + 1L
                var newFloor = oldFloor
                for (row in rows) {
                    val streamSeq = row[SyncEvents.streamSeq]
                    check(streamSeq == expectedSeq) {
                        "sync event stream $uid is not contiguous at $expectedSeq"
                    }
                    if (!row.isCleanupEligible(cutoff)) break
                    newFloor = streamSeq
                    expectedSeq += 1L
                }
                if (newFloor == oldFloor) {
                    return@newSuspendedTransaction UserCompactionResult.None
                }

                val deleted = SyncEvents.deleteWhere {
                    (SyncEvents.uid eq uid) and
                        (SyncEvents.streamSeq greater oldFloor) and
                        (SyncEvents.streamSeq lessEq newFloor)
                }
                check(deleted.toLong() == newFloor - oldFloor) {
                    "sync event prefix delete was incomplete for $uid"
                }
                val updated = SyncStreams.update({
                    (SyncStreams.uid eq uid) and (SyncStreams.compactedThrough eq oldFloor)
                }) {
                    it[compactedThrough] = newFloor
                }
                check(updated == 1) { "sync event floor update was incomplete for $uid" }

                UserCompactionResult(
                    deletedEvents = deleted,
                    backlogMayRemain =
                        rows.size == retention.maxEventsPerUser && newFloor < upperBound,
                )
            }
        }

    private fun ResultRow.isCleanupEligible(cutoff: Long): Boolean =
        this[SyncEvents.dispatchedAt] != null && this[SyncEvents.createdAt] <= cutoff

    private data class UserCompactionResult(
        val deletedEvents: Int,
        val backlogMayRemain: Boolean,
    ) {
        companion object {
            val None = UserCompactionResult(0, false)
        }
    }

    companion object {
        const val MAX_QUERY_EVENTS = 64
    }
}
